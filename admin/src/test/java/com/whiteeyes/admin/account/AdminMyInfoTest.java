package com.whiteeyes.admin.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 관리자 앱 내 정보 조회·수정(FNC-C-006). 실제 로그인으로 만든 세션 쿠키로 요청한다(AdminPasswordChangeTest와 같은 방식).
 */
// 테스트용 application.yml이 main의 것을 가리므로 세션 테이블 이름은 main과 같은 값을 여기서 준다
@SpringBootTest(properties = "spring.session.jdbc.table-name=SPRING_SESSION_ADMIN")
@AutoConfigureMockMvc
class AdminMyInfoTest {

    private static final String CONTEXT_PATH = "/admin";
    private static final String SESSION_COOKIE = "SESSION";
    private static final String LOGIN_ID = "admin01";
    private static final String PASSWORD = "test-password-1!";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void insertAdmin() {
        jdbcTemplate.update("DELETE FROM SPRING_SESSION_ADMIN_ATTRIBUTES");
        jdbcTemplate.update("DELETE FROM SPRING_SESSION_ADMIN");
        jdbcTemplate.update("DELETE FROM admin_account");
        insertAdmin(LOGIN_ID, "관리자 본인", "ADMIN", false);
        insertAdmin("admin02", "다른 관리자", "ADMIN", false);
    }

    private void insertAdmin(String loginId, String name, String role, boolean mustChangePassword) {
        jdbcTemplate.update(
                "INSERT INTO admin_account (login_id, password, name, email, phone, role, status, must_change_password)"
                        + " VALUES (?, ?, ?, 'admin@example.com', '010-1111-2222', ?, 'ACTIVE', ?)",
                loginId,
                passwordEncoder.encode(PASSWORD),
                name,
                role,
                mustChangePassword);
    }

    private Cookie login() throws Exception {
        Cookie cookie = mockMvc.perform(post("/admin/login")
                        .contextPath(CONTEXT_PATH)
                        .param("username", LOGIN_ID)
                        .param("password", PASSWORD)
                        .with(csrf()))
                .andReturn()
                .getResponse()
                .getCookie(SESSION_COOKIE);
        assertThat(cookie).as("로그인 세션 쿠키").isNotNull();
        return cookie;
    }

    private ResultActions getMe(Cookie session) throws Exception {
        return mockMvc.perform(get("/admin/api/v1/me").contextPath(CONTEXT_PATH).cookie(session));
    }

    private ResultActions patchMe(Cookie session, String body) throws Exception {
        return mockMvc.perform(patch("/admin/api/v1/me")
                .contextPath(CONTEXT_PATH)
                .cookie(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private String column(String loginId, String column) {
        return jdbcTemplate.queryForObject(
                "SELECT " + column + " FROM admin_account WHERE login_id = ?", String.class, loginId);
    }

    @Test
    @DisplayName("FNC-C-006 관리자 앱: 내 정보를 조회하면 role이 함께 나온다")
    void get_includesRole() throws Exception {
        getMe(login())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.loginId").value(LOGIN_ID))
                .andExpect(jsonPath("$.name").value("관리자 본인"))
                .andExpect(jsonPath("$.email").value("admin@example.com"))
                .andExpect(jsonPath("$.phone").value("010-1111-2222"))
                .andExpect(jsonPath("$.role").value("ADMIN"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.mustChangePassword").value(false));
    }

    @Test
    @DisplayName("FNC-C-006 관리자 앱: 수정하면 다시 조회한 값에 반영되고 다른 관리자는 그대로다")
    void patch_updatesOnlyOwnRow() throws Exception {
        Cookie session = login();

        patchMe(session, """
                {"name":"새 이름","email":"new@example.com","phone":"010-3333-4444"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("ADMIN"));

        getMe(session).andExpect(jsonPath("$.name").value("새 이름")).andExpect(jsonPath("$.email").value("new@example.com"));
        assertThat(column("admin02", "name")).isEqualTo("다른 관리자");
        assertThat(column("admin02", "email")).isEqualTo("admin@example.com");
    }

    @Test
    @DisplayName("FNC-C-006 관리자 앱: 보낸 필드만 고치고 빈 문자열은 비운다")
    void patch_partialAndClear() throws Exception {
        patchMe(login(), """
                {"name":"이름만","phone":""}""")
                .andExpect(status().isOk());

        assertThat(column(LOGIN_ID, "name")).isEqualTo("이름만");
        assertThat(column(LOGIN_ID, "email")).isEqualTo("admin@example.com");
        assertThat(column(LOGIN_ID, "phone")).isNull();
    }

    @Test
    @DisplayName("FNC-C-006 관리자 앱: 검증 실패는 400 fieldErrors이고 아이디·역할·상태는 바뀌지 않는다")
    void patch_invalidAndImmutableFields() throws Exception {
        Cookie session = login();

        patchMe(session, """
                {"name":""}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("name"));

        patchMe(session, """
                {"name":"정상","loginId":"hacker","role":"SUPER_ADMIN","status":"DISABLED"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.loginId").value(LOGIN_ID))
                .andExpect(jsonPath("$.role").value("ADMIN"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        assertThat(column(LOGIN_ID, "role")).isEqualTo("ADMIN");
    }

    @Test
    @DisplayName("FNC-C-006 관리자 앱: 로그인하지 않으면 401")
    void anonymous_isUnauthorized() throws Exception {
        mockMvc.perform(get("/admin/api/v1/me").contextPath(CONTEXT_PATH)).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("SCR-C-002 관리자 앱: /admin/me 화면에 내 정보 입력 칸과 비밀번호 영역이 있다")
    void mePage_rendersProfileAndPasswordArea() throws Exception {
        mockMvc.perform(get("/admin/me").contextPath(CONTEXT_PATH).cookie(login()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"profile-form\"")))
                .andExpect(content().string(containsString("id=\"password-form\"")));
    }
}
