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
    @DisplayName("SCR-C-004 이 이슈 범위 밖인 이동 경로·하위 비교·기간 비교·엑셀 버튼은 두지 않는다")
    void graphPage_hasNoOutOfScopeElements() throws Exception {
        // 머리 주석에는 이름이 나오므로 요소(속성·글자)만 본다
        mockMvc.perform(get("/stats/graph"))
                .andExpect(content().string(not(containsString("aria-label=\"이동 경로\""))))
                .andExpect(content().string(not(containsString(">하위 비교"))))
                .andExpect(content().string(not(containsString(">기간 비교<"))))
                .andExpect(content().string(not(containsString(">엑셀 다운로드<"))));
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
    @ValueSource(strings = {"/js/pages/stats-graph.js", "/js/chart/series-chart.js", "/css/stats-graph.css"})
    @DisplayName("SCR-C-004 화면이 쓰는 JS·CSS 파일을 관리자 앱이 내려준다")
    void graphPage_staticFilesServed(String path) throws Exception {
        mockMvc.perform(get(path)).andExpect(status().isOk());
    }
}
