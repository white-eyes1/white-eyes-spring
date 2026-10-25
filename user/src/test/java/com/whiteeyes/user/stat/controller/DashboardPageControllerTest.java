package com.whiteeyes.user.stat.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.whiteeyes.common.account.repository.UserAccountRepository;
import com.whiteeyes.user.support.UserTestAccounts;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 통계 메인(대시보드) 화면 SCR-C-003(FNC-C-008·009·016)과 사용자 공통 헤더 SCR-U-004(FNC-C-002·003)의 사용자 앱 렌더링.
 * 화면 모양은 역할만 붙인 요청으로, 접근 권한(FNC-C-001·003, FNC-U-003)은 실제로 로그인(POST /login)한 세션으로 확인한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class DashboardPageControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private UserAccountRepository userAccountRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private UserTestAccounts accounts;

    @BeforeEach
    void setUp() {
        accounts = new UserTestAccounts(mockMvc, jdbcTemplate, userAccountRepository, passwordEncoder);
        accounts.clear();
    }

    @AfterEach
    void tearDown() {
        accounts.clear();
    }

    @Test
    @DisplayName("SCR-C-003 사용자 앱 /stats에 지역 필터·통계 패널·지도와 그래프 보기·기간 비교 '자세히'가 있다")
    void statsPage_user_hasFilterPanelAndMap() throws Exception {
        mockMvc.perform(get("/stats").with(user("user01").roles("USER")))
                .andExpect(status().isOk())
                .andExpect(view().name("stats/main"))
                .andExpect(content().string(containsString("data-admin=\"false\"")))
                .andExpect(content().string(containsString("id=\"filter-sido\"")))
                .andExpect(content().string(containsString("id=\"filter-sigungu\"")))
                .andExpect(content().string(containsString("id=\"filter-emd\"")))
                .andExpect(content().string(containsString("id=\"stat-panel\"")))
                .andExpect(content().string(containsString("id=\"zone-donut\"")))
                .andExpect(content().string(containsString("id=\"dash-map\"")))
                .andExpect(content().string(containsString("id=\"stream-status\"")))
                .andExpect(content().string(containsString("id=\"graph-link\" href=\"/stats/graph\"")))
                .andExpect(content().string(containsString("id=\"abnormal-detail\" href=\"/stats/compare\"")))
                .andExpect(content().string(containsString("/js/pages/stats-main.js")))
                .andExpect(content().string(containsString("/css/stats-main.css")));
    }

    @Test
    @DisplayName("FNC-C-018 사용자 앱 통계 패널에도 시간대별 혼잡 단계 접기 영역이 있다(두 앱 공통)")
    void statsPage_user_hasHourlyLevelsFold() throws Exception {
        mockMvc.perform(get("/stats").with(user("user01").roles("USER")))
                .andExpect(content().string(containsString("<details class=\"acc fold\" id=\"hourly-levels\">")))
                .andExpect(content().string(containsString("id=\"hourly-levels-bars\"")));
    }

    @Test
    @DisplayName("FNC-C-028 사용자 앱 통계 패널에도 방문 패턴 접기 영역이 있다(두 앱 공통)")
    void statsPage_user_hasVisitPatternFold() throws Exception {
        mockMvc.perform(get("/stats").with(user("user01").roles("USER")))
                .andExpect(content().string(containsString("<details class=\"acc fold\" id=\"visit-pattern\">")))
                .andExpect(content().string(containsString("id=\"visit-pattern-grid\"")));
    }

    @Test
    @DisplayName("FNC-C-029 사용자 앱 통계 패널에도 피크 시간 TOP 3 접기 영역이 있다(두 앱 공통)")
    void statsPage_user_hasPeakHoursFold() throws Exception {
        mockMvc.perform(get("/stats").with(user("user01").roles("USER")))
                .andExpect(content().string(containsString("<details class=\"acc fold\" id=\"peak-trend\">")))
                .andExpect(content().string(containsString("id=\"peak-list\"")));
    }

    @Test
    @DisplayName("FNC-C-019 사용자 앱 통계 패널에도 증감 추세가 있다(두 앱 공통)")
    void statsPage_user_hasTrendFold() throws Exception {
        mockMvc.perform(get("/stats").with(user("user01").roles("USER")))
                .andExpect(content().string(containsString("<details class=\"acc fold\" id=\"peak-trend\">")))
                .andExpect(content().string(containsString("id=\"trend-list\"")));
    }

    @Test
    @DisplayName("SCR-C-003 사용자 앱에는 캠 패널·지역 등록 버튼·실시간 보기 창(영상 기능)·이벤트 종류별 건수(FNC-C-030 관리자 앱만)가 없다")
    void statsPage_user_hasNoAdminParts() throws Exception {
        // 템플릿 머리 주석에 '지역 등록·수정'·'캠 패널' 글자가 있어 요소(속성)로 본다
        mockMvc.perform(get("/stats").with(user("user01").roles("USER")))
                .andExpect(content().string(not(containsString("id=\"deck\""))))
                .andExpect(content().string(not(containsString("id=\"deck-cards\""))))
                .andExpect(content().string(not(containsString("href=\"/zones\""))))
                .andExpect(content().string(not(containsString("href=\"/cameras\""))))
                .andExpect(content().string(not(containsString("id=\"live-modal\""))))
                .andExpect(content().string(not(containsString("data-snapshot-interval-sec"))))
                .andExpect(content().string(not(containsString("id=\"aux-events\""))));
    }

    @Test
    @DisplayName("SCR-U-004 사용자 헤더: 탭 2개(대시보드 선택)와 로그인 아이디·내 정보·CSRF가 붙은 로그아웃(POST) 폼, 관리 메뉴·이벤트 탭은 없다")
    void statsPage_user_rendersUserHeader() throws Exception {
        mockMvc.perform(get("/stats").with(user("user01").roles("USER")))
                .andExpect(content().string(containsString("data-header=\"user\"")))
                .andExpect(content().string(not(containsString("data-header=\"admin\""))))
                .andExpect(content().string(containsString("class=\"tab on\" href=\"/stats\"")))
                .andExpect(content().string(containsString("class=\"tab\" href=\"/stats/compare\"")))
                .andExpect(content().string(not(containsString("이벤트 로그"))))
                .andExpect(content().string(not(containsString("id=\"unchecked-badge\""))))
                .andExpect(content().string(containsString("id=\"level-legend\"")))
                .andExpect(content().string(containsString("user01")))
                .andExpect(content().string(containsString("href=\"/me\"")))
                .andExpect(content().string(containsString("action=\"/logout\"")))
                .andExpect(content().string(containsString("name=\"_csrf\"")))
                .andExpect(content().string(containsString("/js/common/header.js")));
    }

    @Test
    @DisplayName("SCR-C-003 실제 화면에는 시안 표시용 data-api·data-fnc 속성과 .anno 요소를 옮기지 않는다")
    void statsPage_noDesignAnnotations() throws Exception {
        mockMvc.perform(get("/stats").with(user("user01").roles("USER")))
                .andExpect(content().string(not(containsString("data-api"))))
                .andExpect(content().string(not(containsString("data-fnc"))))
                .andExpect(content().string(not(containsString("anno"))));
    }

    @Test
    @DisplayName("FNC-C-001 ACTIVE 사용자가 실제로 로그인하면 /stats로 가고 그 화면을 연다")
    void login_active_opensStats() throws Exception {
        accounts.insert("user02", "ACTIVE", false);
        MvcResult login = accounts.login("user02", UserTestAccounts.PASSWORD);
        Cookie session = login.getResponse().getCookie(UserTestAccounts.SESSION_COOKIE);

        assertThat(login.getResponse().getRedirectedUrl()).isEqualTo("/stats");
        mockMvc.perform(get("/stats").cookie(session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("data-header=\"user\"")))
                .andExpect(content().string(containsString("user02")));
    }

    @Test
    @DisplayName("FNC-C-003 로그인하지 않은 요청은 로그인 화면으로 보낸다")
    void statsPage_anonymous_redirectsToLogin() throws Exception {
        mockMvc.perform(get("/stats")).andExpect(status().isFound()).andExpect(redirectedUrl("/login"));
    }

    @Test
    @DisplayName("FNC-U-003 로그인한 승인 대기 계정은 승인 대기 화면으로 보낸다")
    void statsPage_pending_redirectsToPending() throws Exception {
        Cookie session = accounts.loginAs("wait01", "PENDING", false);

        mockMvc.perform(get("/stats").cookie(session))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/pending"));
    }
}
