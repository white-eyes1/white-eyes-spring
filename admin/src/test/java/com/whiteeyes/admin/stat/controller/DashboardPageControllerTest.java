package com.whiteeyes.admin.stat.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.whiteeyes.admin.security.AdminTestUserConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/** 공통 틀(#136)이 관리자 앱 화면에서 그려지는지 확인한다: SCR-A-013 헤더, FNC-C-003 CSRF meta, FNC-C-025 범례 자리. */
@Import(AdminTestUserConfig.class)
@SpringBootTest
@AutoConfigureMockMvc
class DashboardPageControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("SCR-A-013 로그인한 관리자의 /stats 화면에 CSRF·컨텍스트 경로 meta와 헤더 탭이 있다")
    void statsPage_rendersFrame() throws Exception {
        mockMvc.perform(get("/stats"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("<meta name=\"_csrf\"")))
                .andExpect(content().string(containsString("<meta name=\"context-path\"")))
                .andExpect(content().string(containsString("/css/we.css")))
                .andExpect(content().string(containsString("대시보드")))
                .andExpect(content().string(containsString("href=\"/stats/graph\">통계</a>")))
                .andExpect(content().string(containsString("이벤트 로그")))
                .andExpect(content().string(containsString("관리")))
                .andExpect(content().string(containsString("id=\"level-legend\"")))
                .andExpect(content().string(containsString("/js/common/header.js")));
    }

    @Test
    @DisplayName("SCR-A-013 대시보드 탭이 선택된 상태로 그려지고 미확인 배지는 숨겨져 있다")
    void statsPage_dashboardTabActive_badgeHidden() throws Exception {
        mockMvc.perform(get("/stats"))
                .andExpect(content().string(containsString("class=\"tab on\"")))
                .andExpect(content().string(containsString("id=\"unchecked-badge\" hidden")));
    }

    @Test
    @DisplayName("SCR-A-013 이벤트 로그 탭은 /events로, 미확인 필터(status=UNCHECKED)는 배지 링크에만 걸린다")
    void statsPage_eventsTabHasNoFilter_badgeLinksToUnchecked() throws Exception {
        String html = mockMvc.perform(get("/stats")).andReturn().getResponse().getContentAsString();

        assertThat(html).containsPattern("<a class=\"tab[^\"]*\"[^>]*href=\"/events\">\\s*이벤트 로그");
        assertThat(html).containsPattern("<a[^>]*href=\"/events\\?status=UNCHECKED\"[^>]*id=\"unchecked-badge\"");
        // 탭 링크 안에 배지 링크를 넣지 않는다(a 안의 a는 잘못된 HTML)
        assertThat(html).doesNotContainPattern("이벤트 로그\\s*<a");
        assertThat(html.split("status=UNCHECKED", -1)).hasSize(2);
    }

    @Test
    @DisplayName("SCR-A-013 사용자 메뉴에 로그인 아이디와 내 정보, CSRF가 붙은 로그아웃(POST) 폼이 있다")
    void statsPage_userMenu_hasLogoutForm() throws Exception {
        mockMvc.perform(get("/stats"))
                .andExpect(content().string(containsString("admin01")))
                .andExpect(content().string(containsString("href=\"/me\"")))
                .andExpect(content().string(containsString("action=\"/logout\"")))
                .andExpect(content().string(containsString("name=\"_csrf\"")));
    }

    @Test
    @DisplayName("SCR-A-013 관리자 관리·작업 기록 메뉴는 최고 관리자에게만 보인다")
    void statsPage_superAdminMenus_onlyForSuperAdmin() throws Exception {
        mockMvc.perform(get("/stats"))
                .andExpect(content().string(containsString("구역 관리")))
                .andExpect(content().string(containsString("캠 관리")))
                .andExpect(content().string(containsString("사용자 관리")))
                .andExpect(content().string(not(containsString("관리자 관리"))))
                .andExpect(content().string(not(containsString("작업 기록"))));

        mockMvc.perform(get("/stats").with(user("super01").roles("SUPER_ADMIN")))
                .andExpect(content().string(containsString("관리자 관리")))
                .andExpect(content().string(containsString("작업 기록")));
    }

    @Test
    @DisplayName("SCR-C-003 로그인한 관리자의 /stats에 지역 필터·통계 패널·지도·캠 패널·지역 등록 버튼이 있다")
    void statsPage_admin_hasAllPanels() throws Exception {
        mockMvc.perform(get("/stats"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"filter-sido\"")))
                .andExpect(content().string(containsString("id=\"filter-emd\"")))
                .andExpect(content().string(containsString("id=\"stat-panel\"")))
                .andExpect(content().string(containsString("id=\"zone-donut\"")))
                .andExpect(content().string(containsString("id=\"dash-map\"")))
                .andExpect(content().string(containsString("id=\"deck-cards\"")))
                .andExpect(content().string(containsString("id=\"stream-status\"")))
                .andExpect(content().string(containsString("혼잡도 높은 순")))
                .andExpect(content().string(containsString("지역 등록·수정")))
                .andExpect(content().string(containsString("/js/pages/stats-main.js")))
                .andExpect(content().string(containsString("/css/stats-main.css")));
    }

    @Test
    @DisplayName("SCR-C-003 통계 패널에 비정상 인원 변동 접기 영역(처음엔 접힘)과 기간 비교로 가는 '자세히'가 있다")
    void statsPage_hasAbnormalFold() throws Exception {
        mockMvc.perform(get("/stats"))
                .andExpect(content().string(containsString("<details class=\"acc fold\" id=\"abnormal-box\">")))
                .andExpect(content().string(containsString("비정상 인원 변동")))
                .andExpect(content().string(containsString("id=\"abnormal-list\"")))
                .andExpect(content().string(containsString("id=\"abnormal-detail\" href=\"/stats/graph\"")));
    }

    @Test
    @DisplayName("FNC-C-018 통계 패널에 시간대별 혼잡 단계 접기 영역(처음엔 접힘)과 0~23시 막대 자리가 있고, 화면 모듈을 내려 준다")
    void statsPage_hasHourlyLevelsFold() throws Exception {
        mockMvc.perform(get("/stats"))
                .andExpect(content().string(containsString("<details class=\"acc fold\" id=\"hourly-levels\">")))
                .andExpect(content().string(containsString("시간대별 혼잡 단계")))
                .andExpect(content().string(containsString("id=\"hourly-levels-bars\"")));
        mockMvc.perform(get("/js/pages/stats-main/hourly-levels.js"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("/api/v1/dashboard/hourly-levels")));
    }

    @Test
    @DisplayName("FNC-C-028 통계 패널에 방문 패턴 접기 영역(처음엔 접힘)과 요일×시간 표 자리가 있고, 화면 모듈을 내려 준다")
    void statsPage_hasVisitPatternFold() throws Exception {
        mockMvc.perform(get("/stats"))
                .andExpect(content().string(containsString("<details class=\"acc fold\" id=\"visit-pattern\">")))
                .andExpect(content().string(containsString("방문 패턴 (요일×시간)")))
                .andExpect(content().string(containsString("id=\"visit-pattern-grid\"")));
        mockMvc.perform(get("/js/pages/stats-main/visit-pattern.js"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("/api/v1/dashboard/visit-pattern")));
    }

    @Test
    @DisplayName("FNC-C-029 통계 패널에 피크 시간 TOP 3 접기 영역(처음엔 접힘)과 기간 탭 일·주·월·연이 있고, 화면 모듈을 내려 준다")
    void statsPage_hasPeakHoursFold() throws Exception {
        mockMvc.perform(get("/stats"))
                .andExpect(content().string(containsString("<details class=\"acc fold\" id=\"peak-trend\">")))
                .andExpect(content().string(containsString("피크 시간 TOP 3")))
                .andExpect(content().string(containsString("data-period=\"DAY\">일<")))
                .andExpect(content().string(containsString("data-period=\"WEEK\">주<")))
                .andExpect(content().string(containsString("data-period=\"MONTH\">월<")))
                .andExpect(content().string(containsString("data-period=\"YEAR\">연<")))
                .andExpect(content().string(containsString("id=\"peak-list\"")));
        mockMvc.perform(get("/js/pages/stats-main/peak-hours.js"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("/api/v1/dashboard/peak-hours")))
                .andExpect(content().string(containsString("DEFAULT_PERIOD = 'WEEK'")));
    }

    @Test
    @DisplayName("FNC-C-019 통계 패널 '피크 시간 TOP 3 · 증감 추세' 접기 영역에 증감 추세 자리가 있고, 화면 모듈은 증감 API와 기간 비교 주소를 쓴다")
    void statsPage_hasTrendFold() throws Exception {
        mockMvc.perform(get("/stats"))
                .andExpect(content().string(containsString("<details class=\"acc fold\" id=\"peak-trend\">")))
                .andExpect(content().string(containsString("증감 추세")))
                .andExpect(content().string(containsString("id=\"trend-list\"")));
        mockMvc.perform(get("/js/pages/stats-main/trend.js"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("/api/v1/dashboard/trend")))
                .andExpect(content().string(containsString("/stats/graph?")))
                .andExpect(content().string(not(containsString("/stats/compare"))));
    }

    @Test
    @DisplayName("FNC-C-010 지도 도구에 '이동 흐름 (추정)' 토글과 기간 선택(기본 최근 1시간)이 있고, 화면 모듈과 화살표 층을 내려 준다")
    void statsPage_hasFlowTool() throws Exception {
        mockMvc.perform(get("/stats"))
                .andExpect(content().string(containsString("id=\"map-tools\"")))
                .andExpect(content().string(containsString("id=\"flow-toggle\" aria-pressed=\"false\">이동 흐름 (추정)<")))
                .andExpect(content().string(containsString("<option value=\"1h\" selected>최근 1시간</option>")));
        mockMvc.perform(get("/js/pages/stats-main/flow-tool.js"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("/api/v1/map/flows")));
        mockMvc.perform(get("/js/map/flow-layer.js"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("dashArray")));
    }

    @Test
    @DisplayName("FNC-C-011 지도 도구에 히트맵 토글과 조건(기간·항목 4개·요일 3개)·시간대 슬라이더(0~23)·재생이 있고, 화면 모듈을 내려 준다")
    void statsPage_hasHeatmapTool() throws Exception {
        mockMvc.perform(get("/stats"))
                .andExpect(content().string(containsString("id=\"heatmap-toggle\" aria-pressed=\"false\">히트맵<")))
                .andExpect(content().string(containsString("id=\"heatmap-from\"")))
                .andExpect(content().string(containsString("id=\"heatmap-to\"")))
                .andExpect(content().string(containsString("<option value=\"INFLOW\">유입</option>")))
                .andExpect(content().string(containsString("<option value=\"WEEKEND\">주말</option>")))
                .andExpect(content().string(containsString("id=\"heatmap-hour\" min=\"0\" max=\"23\"")))
                .andExpect(content().string(containsString("id=\"heatmap-play\"")));
        mockMvc.perform(get("/js/pages/stats-main/heatmap-tool.js"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("/api/v1/map/heatmap")))
                .andExpect(content().string(containsString("PLAY_INTERVAL_MS = 1000")));
    }

    @Test
    @DisplayName("FNC-C-030 검수 기준: 통계 패널에 보조 지표 접기 영역 6개가 처음엔 접힌 채로 있다")
    void statsPage_hasAuxMetricFolds() throws Exception {
        mockMvc.perform(get("/stats"))
                .andExpect(content().string(containsString("<details class=\"acc fold\" id=\"aux-flow\">")))
                .andExpect(content().string(containsString("<details class=\"acc fold\" id=\"aux-dwell\">")))
                .andExpect(content().string(containsString("<details class=\"acc fold\" id=\"aux-spread\">")))
                .andExpect(content().string(containsString("<details class=\"acc fold\" id=\"aux-level-time\">")))
                .andExpect(content().string(containsString("<details class=\"acc fold\" id=\"aux-top-zones\">")))
                .andExpect(content().string(containsString("<details class=\"acc fold\" id=\"aux-observe\">")))
                .andExpect(content().string(containsString("인원 변동폭")))
                .andExpect(content().string(containsString("오늘 단계별 머문 시간")))
                .andExpect(content().string(containsString("혼잡 구역 TOP 5")))
                .andExpect(content().string(containsString("관측 상태")))
                .andExpect(content().string(containsString("id=\"aux-observe-zones\"")))
                .andExpect(content().string(containsString("id=\"aux-observe-rate\"")));
    }

    @Test
    @DisplayName("FNC-C-030 처리 순서 7: 관리자 앱 통계 패널에는 오늘 이벤트 종류별 건수 접기 영역이 관측 상태 앞에 접힌 채로 있다")
    void statsPage_hasEventFold() throws Exception {
        String html = mockMvc.perform(get("/stats"))
                .andExpect(content().string(containsString("<details class=\"acc fold\" id=\"aux-events\">")))
                .andExpect(content().string(containsString("오늘 이벤트 종류별 건수")))
                .andExpect(content().string(containsString("id=\"aux-events-body\"")))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(html.indexOf("id=\"aux-events\"")).isLessThan(html.indexOf("id=\"aux-observe\""));
    }

    @Test
    @DisplayName("FNC-C-030 처리 순서 7: 보조 지표 화면 모듈은 이벤트 요약 API를 종류 조건 없이 한 번 읽어 종류별 건수(byType)를 쓴다")
    void auxMetricsModule_readsEventSummaryByType() throws Exception {
        mockMvc.perform(get("/js/pages/stats-main/aux-metrics.js"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("/api/v1/events/summary")))
                .andExpect(content().string(containsString("summary.byType[type]")))
                .andExpect(content().string(not(containsString("types: type"))))
                .andExpect(content().string(containsString("'CONGESTION'")))
                .andExpect(content().string(containsString("'SURGE'")))
                .andExpect(content().string(containsString("'CAM_FAULT'")));
    }

    @Test
    @DisplayName("FNC-C-030 업무 규칙: 유입·유출·체류 접기 영역 제목에 '추정'을 표기한다")
    void statsPage_auxFlowAndDwell_markedEstimated() throws Exception {
        mockMvc.perform(get("/stats"))
                .andExpect(content().string(containsString("방향별 유입·유출 (추정)")))
                .andExpect(content().string(containsString("평균 체류 시간 (추정)")));
    }

    @Test
    @DisplayName("FNC-C-030 보조 지표를 계산하는 화면 모듈을 관리자 앱이 내려 주고, 통계 메인 진입 파일이 불러온다")
    void auxMetricsScript_isServed() throws Exception {
        mockMvc.perform(get("/js/pages/stats-main/aux-metrics.js"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("export function createAuxMetricsPanel")))
                .andExpect(content().string(containsString("/api/v1/stats/series")));
        mockMvc.perform(get("/js/pages/stats-main.js"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("./stats-main/aux-metrics.js")));
    }

    @Test
    @DisplayName("SCR-A-008 관리자 앱 /stats에 실시간 보기 창(영상, 연결 실패 시 다시 시도, 현재 통계, 다시보기)이 있다")
    void statsPage_admin_hasLiveViewModal() throws Exception {
        mockMvc.perform(get("/stats"))
                .andExpect(content().string(containsString("id=\"live-modal\"")))
                .andExpect(content().string(containsString("id=\"live-img\"")))
                .andExpect(content().string(containsString("id=\"live-retry\"")))
                .andExpect(content().string(containsString("id=\"live-count\"")))
                .andExpect(content().string(containsString("id=\"live-density\"")))
                .andExpect(content().string(containsString("id=\"live-playback\"")))
                .andExpect(content().string(containsString("/css/live.css")));
    }

    @Test
    @DisplayName("SCR-C-003 실제 화면에는 시안 표시용 data-api·data-fnc 속성과 .anno 요소를 옮기지 않는다")
    void statsPage_noDesignAnnotations() throws Exception {
        mockMvc.perform(get("/stats"))
                .andExpect(content().string(not(containsString("data-api"))))
                .andExpect(content().string(not(containsString("data-fnc"))))
                .andExpect(content().string(not(containsString("anno"))));
    }

    @Test
    @DisplayName("FNC-A-042 캠 카드 미리보기 갱신 간격(기본 2초)이 화면에 data 속성으로 전달된다")
    void statsPage_passesSnapshotInterval() throws Exception {
        mockMvc.perform(get("/stats"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("data-snapshot-interval-sec=\"2\"")));
    }

    @Test
    @DisplayName("SCR-C-003 로그인하지 않은 요청은 로그인 화면으로 보낸다")
    void statsPage_anonymous_redirectsToLogin() throws Exception {
        mockMvc.perform(get("/admin/stats").contextPath("/admin").with(anonymous()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/login"));
    }
}
