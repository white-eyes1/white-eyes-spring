package com.whiteeyes.admin.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
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
 * 관리자 앱 로그아웃(FNC-C-002). 검수 기준: 로그아웃 뒤 이전 화면 주소로 접근하면 로그인 화면으로 이동.
 * 테스트용 application.yml이 세션 표 이름을 가려서 관리자 앱의 표 이름을 직접 지정한다.
 */
@SpringBootTest(properties = "spring.session.jdbc.table-name=SPRING_SESSION_ADMIN")
@AutoConfigureMockMvc
class AdminLogoutTest {

    private static final String PASSWORD = "test-password-1!";
    private static final String LOGIN_ID = "logout-admin";
    private static final String SESSION_COOKIE = "SESSION";
    private static final String LOGIN_REDIRECT = "/admin/login";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM SPRING_SESSION_ADMIN_ATTRIBUTES");
        jdbcTemplate.update("DELETE FROM SPRING_SESSION_ADMIN");
        jdbcTemplate.update("DELETE FROM admin_account WHERE login_id = ?", LOGIN_ID);
    }

    @Test
    @DisplayName("FNC-C-002 로그아웃하면 /login으로 이동하고 세션 행이 지워진다")
    void logout_redirectsToLoginAndDeletesSession() throws Exception {
        Cookie session = login();
        assertThat(sessionRows()).isEqualTo(1);

        mockMvc.perform(post("/admin/logout").contextPath("/admin").cookie(session).with(csrf()))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", LOGIN_REDIRECT));

        assertThat(sessionRows()).isZero();
    }

    @Test
    @DisplayName("FNC-C-002 검수 기준: 로그아웃 뒤 이전 화면 주소로 접근하면 로그인 화면으로 이동")
    void afterLogout_previousPage_redirectsToLogin() throws Exception {
        Cookie session = login();
        mockMvc.perform(get("/admin/stats").contextPath("/admin").cookie(session)).andExpect(status().isOk());

        mockMvc.perform(post("/admin/logout").contextPath("/admin").cookie(session).with(csrf()))
                .andExpect(status().isFound());

        mockMvc.perform(get("/admin/stats").contextPath("/admin").cookie(session))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", LOGIN_REDIRECT));
    }

    @Test
    @DisplayName("FNC-C-002 세션이 이미 없으면(쿠키 없음·모르는 세션·만료) CSRF 토큰이 없어도 로그인 화면으로 이동")
    void logout_withoutSession_redirectsToLogin() throws Exception {
        mockMvc.perform(post("/admin/logout").contextPath("/admin"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", LOGIN_REDIRECT));

        String unknownId = Base64.getEncoder().encodeToString("unknown-session".getBytes(StandardCharsets.UTF_8));
        mockMvc.perform(post("/admin/logout").contextPath("/admin").cookie(new Cookie(SESSION_COOKIE, unknownId)))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", LOGIN_REDIRECT));

        Cookie session = login();
        jdbcTemplate.update("UPDATE SPRING_SESSION_ADMIN SET LAST_ACCESS_TIME = 1, EXPIRY_TIME = 1");
        mockMvc.perform(post("/admin/logout").contextPath("/admin").cookie(session))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", LOGIN_REDIRECT));
    }

    @Test
    @DisplayName("FNC-C-002 세션이 있는데 CSRF 토큰이 없으면 403이고 세션은 남는다")
    void logout_withSessionButNoCsrfToken_forbidden() throws Exception {
        Cookie session = login();

        mockMvc.perform(post("/admin/logout").contextPath("/admin").cookie(session))
                .andExpect(status().isForbidden());

        assertThat(sessionRows()).isEqualTo(1);
    }

    @Test
    @DisplayName("FNC-C-002 CSRF 검사를 건너뛰는 것은 세션 없는 POST /logout뿐이다(세션 없는 다른 POST는 403)")
    void csrfSkip_onlyForLogout() throws Exception {
        mockMvc.perform(post("/admin/api/v1/anything").contextPath("/admin")).andExpect(status().isForbidden());
        mockMvc.perform(post("/admin/logout/other").contextPath("/admin")).andExpect(status().isForbidden());
    }

    // 로그인 화면으로 실제 로그인해 받은 세션 쿠키
    private Cookie login() throws Exception {
        jdbcTemplate.update(
                "INSERT INTO admin_account (login_id, password, name, role, status, must_change_password)"
                        + " VALUES (?, ?, '로그아웃 테스트', 'ADMIN', 'ACTIVE', 0)",
                LOGIN_ID,
                passwordEncoder.encode(PASSWORD));
        Cookie session = mockMvc.perform(post("/admin/login")
                        .contextPath("/admin")
                        .param("username", LOGIN_ID)
                        .param("password", PASSWORD)
                        .with(csrf()))
                .andReturn()
                .getResponse()
                .getCookie(SESSION_COOKIE);
        assertThat(session).as("로그인 뒤 세션 쿠키").isNotNull();
        return session;
    }

    private long sessionRows() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM SPRING_SESSION_ADMIN", Long.class);
    }
}
