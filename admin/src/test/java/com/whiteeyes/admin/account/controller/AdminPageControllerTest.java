package com.whiteeyes.admin.account.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

// AdminListTest와 같이 계정마다 로그인 화면(POST /login)으로 실제 로그인해 세션 쿠키로 연다
/** 관리자 관리 화면 SCR-A-003(FNC-A-003): 최고 관리자만 열리고, 표·페이지 자리와 화면 JS가 있다 */
@SpringBootTest(properties = "spring.session.jdbc.table-name=SPRING_SESSION_ADMIN")
@AutoConfigureMockMvc
class AdminPageControllerTest {

    private static final String CONTEXT_PATH = "/admin";
    private static final String PAGE_URL = "/admin/admins";
    private static final String PASSWORD = "test-password-1!";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void setUp() {
        cleanTables();
        insertAccount("super01", "SUPER_ADMIN");
        insertAccount("admin01", "ADMIN");
    }

    @AfterEach
    void tearDown() {
        cleanTables();
    }

    @Test
    @DisplayName("SCR-A-003 최고 관리자로 로그인하면 관리자 표(아이디·이름·역할·상태·최근 활동·활동 중)와 페이지 자리가 있다")
    void superAdmin_rendersTable() throws Exception {
        mockMvc.perform(get(PAGE_URL).contextPath(CONTEXT_PATH).cookie(login("super01")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("관리자 관리")))
                .andExpect(content().string(containsString("id=\"admin-table\"")))
                .andExpect(content().string(containsString("data-sort=\"loginId\"")))
                .andExpect(content().string(containsString("<th scope=\"col\">역할</th>")))
                .andExpect(content().string(containsString("<th scope=\"col\">최근 활동</th>")))
                .andExpect(content().string(containsString("<th scope=\"col\">활동 중</th>")))
                .andExpect(content().string(containsString("id=\"admin-pagination\"")))
                .andExpect(content().string(containsString("/js/admin/pages/admins.js")))
                .andExpect(content().string(containsString("/css/admin/admins.css")));
    }

    @Test
    @DisplayName("SCR-A-003 일반 관리자로 로그인해 열면 403 화면")
    void admin_forbidden() throws Exception {
        mockMvc.perform(get(PAGE_URL).contextPath(CONTEXT_PATH).cookie(login("admin01")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("SCR-A-003 로그인하지 않으면 로그인 화면으로 보낸다")
    void anonymous_redirectsToLogin() throws Exception {
        mockMvc.perform(get(PAGE_URL).contextPath(CONTEXT_PATH))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/login"));
    }

    @Test
    @DisplayName("SCR-A-003 다른 기능의 버튼과 시안 표시용 속성은 옮기지 않는다")
    void noOtherFeaturesOrAnnotations() throws Exception {
        mockMvc.perform(get(PAGE_URL).contextPath(CONTEXT_PATH).cookie(login("super01")))
                .andExpect(content().string(not(containsString("관리자 추가"))))
                .andExpect(content().string(not(containsString("강제 로그아웃"))))
                .andExpect(content().string(not(containsString("data-api"))))
                .andExpect(content().string(not(containsString("data-fnc"))))
                .andExpect(content().string(not(containsString("anno"))));
    }

    private Cookie login(String loginId) throws Exception {
        Cookie session = mockMvc.perform(post("/admin/login")
                        .contextPath(CONTEXT_PATH)
                        .param("username", loginId)
                        .param("password", PASSWORD)
                        .with(csrf()))
                .andExpect(status().isFound())
                .andReturn()
                .getResponse()
                .getCookie("SESSION");
        assertThat(session).as(loginId + " 로그인 세션").isNotNull();
        return session;
    }

    private void cleanTables() {
        jdbcTemplate.update("DELETE FROM SPRING_SESSION_ADMIN_ATTRIBUTES");
        jdbcTemplate.update("DELETE FROM SPRING_SESSION_ADMIN");
        jdbcTemplate.update("DELETE FROM admin_account");
    }

    private void insertAccount(String loginId, String role) {
        jdbcTemplate.update(
                "INSERT INTO admin_account (login_id, password, name, role, status, must_change_password)"
                        + " VALUES (?, ?, '테스트 관리자', ?, 'ACTIVE', 0)",
                loginId,
                passwordEncoder.encode(PASSWORD),
                role);
    }
}
