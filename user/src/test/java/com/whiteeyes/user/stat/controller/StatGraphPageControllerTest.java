package com.whiteeyes.user.stat.controller;

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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 통계 그래프 화면 SCR-C-004(FNC-C-012)의 사용자 앱 렌더링.
 * 화면 모양은 역할만 붙인 요청으로, 접근 권한(FNC-C-003, FNC-U-003)은 실제로 로그인(POST /login)한 세션으로 확인한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class StatGraphPageControllerTest {

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
    @DisplayName("SCR-C-004 사용자 앱 /stats/graph는 사용자 헤더(SCR-U-004)의 '통계·기간 비교' 탭을 켜고 그래프 조건·차트 자리를 그린다")
    void graphPage_user_rendersUserHeaderAndConditions() throws Exception {
        mockMvc.perform(get("/stats/graph").with(user("user01").roles("USER")))
                .andExpect(status().isOk())
                .andExpect(view().name("stats/graph"))
                .andExpect(content().string(containsString("<title>통계 그래프 - White Eyes</title>")))
                .andExpect(content().string(containsString("data-header=\"user\"")))
                .andExpect(content().string(not(containsString("data-header=\"admin\""))))
                .andExpect(content().string(containsString("class=\"tab on\" href=\"/stats/compare\"")))
                .andExpect(content().string(containsString("id=\"target-sido\"")))
                .andExpect(content().string(containsString("id=\"target-zone\"")))
                .andExpect(content().string(containsString("data-interval=\"1m\"")))
                .andExpect(content().string(containsString("id=\"series-chart\"")))
                .andExpect(content().string(containsString("src=\"https://unpkg.com/chart.js@4.4.7/dist/chart.umd.js\"")))
                .andExpect(content().string(containsString("src=\"/js/pages/stats-graph.js\"")))
                .andExpect(content().string(containsString("href=\"/css/stats-graph.css\"")));
    }

    @Test
    @DisplayName("SCR-C-004 실제 화면에는 시안 표시용 data-api·data-fnc 속성과 .anno 요소를 옮기지 않는다")
    void graphPage_noDesignAnnotations() throws Exception {
        mockMvc.perform(get("/stats/graph").with(user("user01").roles("USER")))
                .andExpect(content().string(not(containsString("data-api"))))
                .andExpect(content().string(not(containsString("data-fnc"))))
                .andExpect(content().string(not(containsString("anno"))));
    }

    @Test
    @DisplayName("SCR-C-004 그래프 패널 머리에 이동 경로, 그래프 안에 점 선택 창 자리가 있다(FNC-C-013)")
    void graphPage_user_hasDrillDown() throws Exception {
        mockMvc.perform(get("/stats/graph").with(user("user01").roles("USER")))
                .andExpect(content().string(containsString("id=\"drill-path\" aria-label=\"이동 경로\"")))
                .andExpect(content().string(containsString("id=\"point-menu\"")))
                .andExpect(content().string(containsString("id=\"point-menu-notice\"")));
    }

    // stats-graph.js·series-chart.js·stats-graph.css는 common 모듈에 있어 사용자 앱에서도 내려가는지 본다
    @ParameterizedTest
    @ValueSource(strings = {
        "/js/pages/stats-graph.js", "/js/pages/stats-graph/drill.js", "/js/chart/series-chart.js", "/css/stats-graph.css"
    })
    @DisplayName("SCR-C-004 화면이 쓰는 JS·CSS 파일을 사용자 앱이 내려준다")
    void graphPage_staticFilesServed(String path) throws Exception {
        mockMvc.perform(get(path)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("FNC-C-003 로그인한 USER는 실제 세션으로 /stats/graph를 연다")
    void graphPage_loggedInUser_ok() throws Exception {
        Cookie session = accounts.loginAs("user02", "ACTIVE", false);

        mockMvc.perform(get("/stats/graph").cookie(session)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("FNC-C-003 로그인하지 않은 요청은 로그인 화면으로 보낸다")
    void graphPage_anonymous_redirectsToLogin() throws Exception {
        mockMvc.perform(get("/stats/graph")).andExpect(status().isFound()).andExpect(redirectedUrl("/login"));
    }

    @Test
    @DisplayName("FNC-U-003 로그인한 승인 대기 계정은 승인 대기 화면으로 보낸다")
    void graphPage_pending_redirectsToPending() throws Exception {
        Cookie session = accounts.loginAs("wait01", "PENDING", false);

        mockMvc.perform(get("/stats/graph").cookie(session))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/pending"));
    }
}
