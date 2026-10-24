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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 기간 비교 분석 화면 SCR-C-005(FNC-C-020)의 사용자 앱 렌더링.
 * 화면 모양은 역할만 붙인 요청으로, 접근 권한(FNC-C-003, FNC-U-003)은 실제로 로그인(POST /login)한 세션으로 확인한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ComparePageControllerTest {

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
    @DisplayName("SCR-C-005 사용자 앱 /stats/compare에 조건(대상·기간·비정상 기준), 전체 증감률, 비교표가 있다")
    void comparePage_user_hasConditionsAndResult() throws Exception {
        mockMvc.perform(get("/stats/compare").with(user("user01").roles("USER")))
                .andExpect(status().isOk())
                .andExpect(view().name("stats/compare"))
                .andExpect(content().string(containsString("기간 비교 분석")))
                .andExpect(content().string(containsString("id=\"target-sido\"")))
                .andExpect(content().string(containsString("id=\"target-zone\"")))
                .andExpect(content().string(containsString("id=\"target-from\"")))
                .andExpect(content().string(containsString("id=\"target-to\"")))
                .andExpect(content().string(containsString("id=\"threshold\"")))
                .andExpect(content().string(containsString("id=\"total-rate\"")))
                .andExpect(content().string(containsString("id=\"compare-grid\"")))
                .andExpect(content().string(containsString("/js/pages/stats-compare.js")))
                .andExpect(content().string(containsString("/css/stats-compare.css")));
    }

    @Test
    @DisplayName("SCR-U-004 사용자 헤더의 '통계·기간 비교' 탭이 선택된 상태로 그려지고 관리자 헤더는 쓰지 않는다")
    void comparePage_user_statsTabActive() throws Exception {
        mockMvc.perform(get("/stats/compare").with(user("user01").roles("USER")))
                .andExpect(content().string(containsString("data-header=\"user\"")))
                .andExpect(content().string(not(containsString("data-header=\"admin\""))))
                .andExpect(content().string(containsString("class=\"tab on\" href=\"/stats/compare\"")));
    }

    @Test
    @DisplayName("SCR-C-005 실제 화면에는 시안 표시용 data-api·data-fnc 속성과 .anno 요소를 옮기지 않는다")
    void comparePage_noDesignAnnotations() throws Exception {
        mockMvc.perform(get("/stats/compare").with(user("user01").roles("USER")))
                .andExpect(content().string(not(containsString("data-api"))))
                .andExpect(content().string(not(containsString("data-fnc"))))
                .andExpect(content().string(not(containsString("anno"))));
    }

    @Test
    @DisplayName("FNC-C-003 로그인한 USER는 실제 세션으로 /stats/compare를 연다")
    void comparePage_loggedInUser_ok() throws Exception {
        Cookie session = accounts.loginAs("user02", "ACTIVE", false);

        mockMvc.perform(get("/stats/compare").cookie(session)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("FNC-C-003 로그인하지 않은 요청은 로그인 화면으로 보낸다")
    void comparePage_anonymous_redirectsToLogin() throws Exception {
        mockMvc.perform(get("/stats/compare")).andExpect(status().isFound()).andExpect(redirectedUrl("/login"));
    }

    @Test
    @DisplayName("FNC-U-003 로그인한 승인 대기 계정은 승인 대기 화면으로 보낸다")
    void comparePage_pending_redirectsToPending() throws Exception {
        Cookie session = accounts.loginAs("wait01", "PENDING", false);

        mockMvc.perform(get("/stats/compare").cookie(session))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/pending"));
    }
}
