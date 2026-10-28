package com.whiteeyes.admin.stat.controller;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.whiteeyes.admin.security.AdminTestUserConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/** 통계 그래프 화면 SCR-C-004(FNC-C-012)와 통계 메인의 '그래프 보기' 버튼 */
@Import(AdminTestUserConfig.class)
@SpringBootTest
@AutoConfigureMockMvc
class StatGraphPageControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("SCR-C-004 /stats/graph는 공통 틀 위에 '통계·기간 비교' 탭을 켜고, Chart.js는 고정 버전을 이 화면에서만 불러온다")
    void graphPage_rendersFrameAndChartLibrary() throws Exception {
        mockMvc.perform(get("/stats/graph"))
                .andExpect(status().isOk())
                .andExpect(view().name("stats/graph"))
                .andExpect(content().string(containsString("<title>통계 그래프 - White Eyes</title>")))
                .andExpect(content().string(containsString("data-header=\"admin\"")))
                .andExpect(content().string(containsString("class=\"tab on\" href=\"/stats/compare\"")))
                .andExpect(content().string(containsString("src=\"https://unpkg.com/chart.js@4.4.7/dist/chart.umd.js\"")))
                .andExpect(content().string(containsString("href=\"/css/stats-graph.css\"")))
                .andExpect(content().string(containsString("src=\"/js/common/header.js\"")))
                .andExpect(content().string(containsString("src=\"/js/pages/stats-graph.js\"")));

        // 통계 메인에는 Chart.js를 넣지 않는다
        mockMvc.perform(get("/stats")).andExpect(content().string(not(containsString("chart.js@"))));
    }

    @Test
    @DisplayName("SCR-C-004 대상(시·구·동·구역)·단위 탭 6개·기간·항목 체크박스 5개(인원은 하나)가 있다")
    void graphPage_hasConditions() throws Exception {
        String[] expected = {
            "id=\"target-sido\"", "id=\"target-sigungu\"", "id=\"target-emd\"", "id=\"target-zone\"",
            "data-interval=\"1m\"", "data-interval=\"1h\"", "data-interval=\"1d\"",
            "data-interval=\"1w\"", "data-interval=\"1M\"", "data-interval=\"1y\"",
            "id=\"period-from\"", "id=\"period-end\"",
            "value=\"count\"", "value=\"density\"",
            "value=\"inflow\"", "value=\"outflow\"", "value=\"dwellAvgSec\"",
            "id=\"series-chart\"", "id=\"graph-empty\"", "id=\"graph-error\""
        };
        var result = mockMvc.perform(get("/stats/graph")).andExpect(status().isOk());
        for (String fragment : expected) {
            result.andExpect(content().string(containsString(fragment)));
        }
        // 인원 평균·최대·최소를 따로 고르는 체크박스는 없다(인원 하나로 평균 선 + 최대·최소 띠)
        result.andExpect(content().string(not(containsString("value=\"countAvg\""))))
                .andExpect(content().string(not(containsString("value=\"countMax\""))))
                .andExpect(content().string(not(containsString("value=\"countMin\""))));
    }

    @Test
    @DisplayName("FNC-C-024 제목 옆에 '시연용 데이터 포함' 배지 자리가 있고 처음엔 숨겨져 있다(시연용 점이 있을 때 화면 JS가 보임)")
    void graphPage_hasDemoBadge() throws Exception {
        mockMvc.perform(get("/stats/graph"))
                .andExpect(content().string(containsString(
                        "<span class=\"chip demo\" id=\"demo-badge\" hidden>시연용 데이터 포함</span>")));
        // 시연용 점은 점선이 아니라 칸 배경으로 칠한다(결정 0016)
        mockMvc.perform(get("/js/chart/series-chart.js"))
                .andExpect(content().string(containsString("shadePlugin(demo, DEMO_FILL)")));
    }

    @Test
    @DisplayName("SCR-C-004 통합 그래프 아래에 혼잡 단계 막대·인원·유입·유출·방향별 흐름 칸과 최대·최소 띠 토글이 있다(결정 0016)")
    void graphPage_hasPanelsBelowMainChart() throws Exception {
        String[] expected = {
            "id=\"band-toggle\"", ">최대·최소 띠", "id=\"graph-panels\"",
            "<h2>시간별 인원 · 혼잡 단계</h2>", "id=\"level-chart\"",
            "<h2>인원 · 유입·유출</h2>", "id=\"count-chart\"", "id=\"flow-chart\"",
            "<h2>방향별 흐름 <span class=\"muted\">선택한 기간 합계</span></h2>", "id=\"direction-chart\"", "id=\"direction-empty\""
        };
        var result = mockMvc.perform(get("/stats/graph")).andExpect(status().isOk());
        for (String fragment : expected) {
            result.andExpect(content().string(containsString(fragment)));
        }
        // 띠 토글은 항목 체크박스 묶음 밖에 있다(항목 선택에 끼지 않음)
        String html = result.andReturn().getResponse().getContentAsString();
        int itemsEnd = html.indexOf("</div>", html.indexOf("id=\"graph-items\""));
        org.assertj.core.api.Assertions.assertThat(html.indexOf("id=\"band-toggle\"")).isGreaterThan(itemsEnd);
        // 혼잡 단계 색은 서버가 판정한 point.level을 쓴다(화면에서 다시 판정하지 않음)
        mockMvc.perform(get("/js/chart/panel-charts.js"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("point.level")));
    }

    @Test
    @DisplayName("SCR-C-004 이 이슈 범위 밖인 기간 비교 버튼은 두지 않는다")
    void graphPage_hasNoOutOfScopeElements() throws Exception {
        // 머리 주석에는 이름이 나오므로 요소(글자)만 본다. 엑셀 버튼은 #28, 하위 비교는 #57, 이동 경로는 #56에서 붙였다
        mockMvc.perform(get("/stats/graph"))
                .andExpect(content().string(not(containsString(">기간 비교<"))));
    }

    @Test
    @DisplayName("SCR-C-004 그래프 패널 머리에 이동 경로, 그래프 안에 1시간 점 선택 창 자리가 있다(FNC-C-013)")
    void graphPage_hasDrillDown() throws Exception {
        String[] expected = {
            "id=\"drill-path\" aria-label=\"이동 경로\"", "id=\"point-menu\"", "id=\"point-menu-title\"",
            "id=\"point-menu-summary\"", "id=\"point-menu-actions\"", "id=\"point-menu-notice\""
        };
        var result = mockMvc.perform(get("/stats/graph")).andExpect(status().isOk());
        for (String fragment : expected) {
            result.andExpect(content().string(containsString(fragment)));
        }
    }

    @Test
    @DisplayName("SCR-C-004 그래프 패널 머리에 하위 비교 토글과 항목 선택(인원·밀집도·유입·유출·평균 체류)이 있다(FNC-C-014)")
    void graphPage_hasChildrenCompare() throws Exception {
        String[] expected = {
            ">하위 비교", "id=\"compare-toggle\"", "role=\"switch\"", "id=\"compare-item\"", "id=\"compare-omitted\"",
            "<option value=\"COUNT\">인원</option>", "<option value=\"DENSITY\">밀집도</option>",
            "<option value=\"INFLOW\">유입</option>", "<option value=\"OUTFLOW\">유출</option>",
            "<option value=\"DWELL\">평균 체류</option>"
        };
        var result = mockMvc.perform(get("/stats/graph")).andExpect(status().isOk());
        for (String fragment : expected) {
            result.andExpect(content().string(containsString(fragment)));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"/stats/graph", "/stats"})
    @DisplayName("SCR-C-003·004 '엑셀 다운로드' 버튼과 엑셀 다운로드 창(SCR-C-006, FNC-C-023)이 있다")
    void graphAndStatsPage_haveExportButton(String path) throws Exception {
        String[] expected = {
            "id=\"export-open\"", ">엑셀 다운로드<", "id=\"export-modal\"", "id=\"export-scope\"",
            "id=\"export-expand\"", ">체크하지 않으면 지역 합산 한 줄로 받습니다<", "id=\"export-from\"", "id=\"export-end\"", "id=\"export-interval\"",
            "value=\"COUNT\"", "value=\"DENSITY\"", "value=\"INFLOW\"", "value=\"OUTFLOW\"", "value=\"DWELL\"",
            "id=\"export-submit\"", "href=\"/css/stats-export.css\""
        };
        var result = mockMvc.perform(get(path)).andExpect(status().isOk());
        for (String fragment : expected) {
            result.andExpect(content().string(containsString(fragment)));
        }
        // 시안의 설명 요소·속성은 옮기지 않는다
        result.andExpect(content().string(not(containsString("data-fnc="))))
                .andExpect(content().string(not(containsString("data-api="))));
    }

    @Test
    @DisplayName("SCR-C-003 통계 메인 통계 패널에 '그래프 보기' 버튼이 있고 관리자 앱 그래프 화면을 가리킨다")
    void statsPage_hasGraphLink() throws Exception {
        mockMvc.perform(get("/stats"))
                .andExpect(content().string(containsString("id=\"graph-link\" href=\"/stats/graph\"")))
                .andExpect(content().string(containsString("그래프 보기")));
    }

    // stats-graph.js·series-chart.js·stats-graph.css는 common 모듈에 있어 관리자 앱에서 내려가는지 본다
    @ParameterizedTest
    @ValueSource(strings = {
        "/js/pages/stats-graph.js", "/js/pages/stats-graph/drill.js", "/js/chart/series-chart.js",
        "/js/chart/chart-theme.js", "/js/chart/panel-charts.js", "/css/stats-graph.css",
        "/js/export/export-modal.js", "/css/stats-export.css"
    })
    @DisplayName("SCR-C-004 화면이 쓰는 JS·CSS 파일을 관리자 앱이 내려준다")
    void graphPage_staticFilesServed(String path) throws Exception {
        mockMvc.perform(get(path)).andExpect(status().isOk());
    }
}
