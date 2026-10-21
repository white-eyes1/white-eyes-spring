package com.whiteeyes.admin.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
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
 * 실제 로그인으로 만든 세션의 역할이 권한 표에 맞게 적용되는지 본다(FNC-C-003). 로그인 때 계정의 role이
 * 권한(ROLE_…)으로 바뀌는 부분(FNC-C-001)과 권한 표가 같은 이름을 쓰는지 가짜 사용자 없이 확인한다.
 */
@SpringBootTest(properties = "spring.session.jdbc.table-name=SPRING_SESSION_ADMIN")
@AutoConfigureMockMvc
class AdminRoleSessionTest {

    private static final String CONTEXT_PATH = "/admin";
    private static final String PASSWORD = "test-password-1!";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void cleanTables() {
        jdbcTemplate.update("DELETE FROM SPRING_SESSION_ADMIN_ATTRIBUTES");
        jdbcTemplate.update("DELETE FROM SPRING_SESSION_ADMIN");
        jdbcTemplate.update("DELETE FROM admin_account");
    }

    private Cookie loginAs(String loginId, String role, boolean mustChangePassword) throws Exception {
        jdbcTemplate.update(
                "INSERT INTO admin_account (login_id, password, name, role, status, must_change_password)"
                        + " VALUES (?, ?, ?, ?, 'ACTIVE', ?)",
                loginId,
                passwordEncoder.encode(PASSWORD),
                "테스트 관리자",
                role,
                mustChangePassword);
        MvcResult login = mockMvc.perform(post("/admin/login")
                        .contextPath(CONTEXT_PATH)
                        .param("username", loginId)
                        .param("password", PASSWORD)
                        .with(csrf()))
                .andReturn();
        Cookie session = login.getResponse().getCookie("SESSION");
        assertThat(session).as("로그인 뒤 세션 쿠키").isNotNull();
        return session;
    }

    @Test
    @DisplayName("FNC-C-003 로그인한 ADMIN은 최고 관리자 전용 API가 403이고 일반 경로는 열린다")
    void loggedInAdmin_superOnlyForbidden_othersOpen() throws Exception {
        Cookie session = loginAs("admin01", "ADMIN", false);

        mockMvc.perform(get("/admin/api/v1/admins").contextPath(CONTEXT_PATH).cookie(session))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mockMvc.perform(get("/admin/api/v1/events").contextPath(CONTEXT_PATH).cookie(session))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("FNC-C-003 로그인한 SUPER_ADMIN은 최고 관리자 전용 경로도 열린다")
    void loggedInSuperAdmin_allOpen() throws Exception {
        Cookie session = loginAs("super01", "SUPER_ADMIN", false);

        mockMvc.perform(get("/admin/api/v1/admins").contextPath(CONTEXT_PATH).cookie(session))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/admin/api/v1/events").contextPath(CONTEXT_PATH).cookie(session))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("FNC-C-003 비밀번호 변경이 필요한 계정으로 로그인하면 다른 API는 403, 변경 화면은 열린다")
    void loggedInMustChange_otherApiForbidden_changeScreenOpen() throws Exception {
        Cookie session = loginAs("super02", "SUPER_ADMIN", true);

        mockMvc.perform(get("/admin/api/v1/events").contextPath(CONTEXT_PATH).cookie(session))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/admin/me/password").contextPath(CONTEXT_PATH).cookie(session))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("FNC-C-003 로그인하지 않은 요청이 세션 행을 만들지 않는다")
    void anonymousRequest_createsNoSession() throws Exception {
        mockMvc.perform(get("/admin/stats").contextPath(CONTEXT_PATH)).andExpect(status().isFound());

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM SPRING_SESSION_ADMIN", Integer.class))
                .isZero();
    }
}
