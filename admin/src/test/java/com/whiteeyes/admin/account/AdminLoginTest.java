package com.whiteeyes.admin.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.time.LocalDateTime;
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
 * 관리자 앱 로그인(FNC-C-001). 관리자 앱은 컨텍스트 경로가 /admin이라 요청에 같은 경로를 붙여 보낸다.
 */
// 테스트용 application.yml이 main의 것을 가리므로 세션 테이블 이름은 main과 같은 값을 여기서 준다(AdminSessionConfigTest가 main 값을 확인).
// MockMvc에는 내장 서버가 없어 쿠키 이름 설정이 적용되지 않는다. 실제 쿠키 이름은 AdminLoginCookieTest가 확인한다
@SpringBootTest(properties = "spring.session.jdbc.table-name=SPRING_SESSION_ADMIN")
@AutoConfigureMockMvc
class AdminLoginTest {

    private static final String CONTEXT_PATH = "/admin";
    private static final String SESSION_COOKIE = "SESSION";
    private static final String PASSWORD = "test-password-1!";
    private static final String INVALID_MESSAGE = "아이디 또는 비밀번호가 올바르지 않습니다.";
    private static final String DISABLED_MESSAGE = "사용이 중지된 계정입니다.";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void cleanTables() {
        // 앱이 켜질 때 만들어진 최고 관리자와 이전 테스트의 세션을 비우고 시작한다
        jdbcTemplate.update("DELETE FROM SPRING_SESSION_ADMIN_ATTRIBUTES");
        jdbcTemplate.update("DELETE FROM SPRING_SESSION_ADMIN");
        jdbcTemplate.update("DELETE FROM admin_account");
    }

    private void insertAccount(String loginId, String role, String status, boolean mustChangePassword) {
        jdbcTemplate.update(
                "INSERT INTO admin_account (login_id, password, name, role, status, must_change_password)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                loginId,
                passwordEncoder.encode(PASSWORD),
                "테스트 관리자",
                role,
                status,
                mustChangePassword);
    }

    private MvcResult login(String loginId, String password) throws Exception {
        return mockMvc.perform(post("/admin/login")
                        .contextPath(CONTEXT_PATH)
                        .param("username", loginId)
                        .param("password", password)
                        .with(csrf()))
                .andReturn();
    }

    private String loginPageAfterFailure(MvcResult failure) throws Exception {
        Cookie session = failure.getResponse().getCookie(SESSION_COOKIE);
        assertThat(session).as("실패해도 오류 문구를 담을 세션 쿠키").isNotNull();
        return mockMvc.perform(get("/admin/login").contextPath(CONTEXT_PATH).cookie(session))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    private LocalDateTime lastLoginAt(String loginId) {
        return jdbcTemplate.queryForObject(
                "SELECT last_login_at FROM admin_account WHERE login_id = ?", LocalDateTime.class, loginId);
    }

    @Test
    @DisplayName("FNC-C-001 ACTIVE 관리자는 로그인하고 통계 메인으로 이동한다")
    void login_activeAdmin_redirectsToStats() throws Exception {
        insertAccount("admin01", "ADMIN", "ACTIVE", false);

        mockMvc.perform(post("/admin/login")
                        .contextPath(CONTEXT_PATH)
                        .param("username", "admin01")
                        .param("password", PASSWORD)
                        .with(csrf()))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/admin/stats"))
                .andExpect(cookie().exists(SESSION_COOKIE));
    }

    @Test
    @DisplayName("FNC-C-001 ACTIVE 최고 관리자도 로그인한다")
    void login_activeSuperAdmin_redirectsToStats() throws Exception {
        insertAccount("super01", "SUPER_ADMIN", "ACTIVE", false);

        assertThat(login("super01", PASSWORD).getResponse().getRedirectedUrl()).isEqualTo("/admin/stats");
    }

    @Test
    @DisplayName("FNC-C-001 must_change_password=1이면 비밀번호 변경 화면으로 이동한다")
    void login_mustChangePassword_redirectsToPasswordChange() throws Exception {
        insertAccount("super02", "SUPER_ADMIN", "ACTIVE", true);

        assertThat(login("super02", PASSWORD).getResponse().getRedirectedUrl()).isEqualTo("/admin/me/password");
    }

    @Test
    @DisplayName("FNC-C-001 DISABLED 계정은 비밀번호가 맞아도 거부하고 사용 중지 문구를 보인다")
    void login_disabledAccount_rejectedWithDisabledMessage() throws Exception {
        insertAccount("off01", "ADMIN", "DISABLED", false);

        MvcResult failure = login("off01", PASSWORD);

        assertThat(failure.getResponse().getRedirectedUrl()).isEqualTo("/admin/login?error");
        assertThat(loginPageAfterFailure(failure)).contains(DISABLED_MESSAGE);
        assertThat(lastLoginAt("off01")).isNull();
    }

    @Test
    @DisplayName("FNC-C-001 DISABLED 최고 관리자도 거부한다(비밀번호 변경 필요 상태여도 같다)")
    void login_disabledSuperAdmin_rejected() throws Exception {
        insertAccount("off03", "SUPER_ADMIN", "DISABLED", true);

        MvcResult failure = login("off03", PASSWORD);

        assertThat(failure.getResponse().getRedirectedUrl()).isEqualTo("/admin/login?error");
        assertThat(loginPageAfterFailure(failure)).contains(DISABLED_MESSAGE);
    }

    @Test
    @DisplayName("FNC-C-001 DISABLED 계정에 틀린 비밀번호를 보내면 계정 상태를 알려 주지 않는다")
    void login_disabledAccountWithWrongPassword_doesNotRevealStatus() throws Exception {
        insertAccount("off02", "ADMIN", "DISABLED", false);

        MvcResult failure = login("off02", "wrong-password-9");

        assertThat(loginPageAfterFailure(failure)).contains(INVALID_MESSAGE).doesNotContain(DISABLED_MESSAGE);
    }

    @Test
    @DisplayName("FNC-C-001 틀린 비밀번호는 하나의 문구로 거부하고 입력한 아이디를 유지한다")
    void login_wrongPassword_rejectedAndKeepsLoginId() throws Exception {
        insertAccount("admin02", "ADMIN", "ACTIVE", false);

        MvcResult failure = login("admin02", "wrong-password-9");

        assertThat(failure.getResponse().getRedirectedUrl()).isEqualTo("/admin/login?error");
        String page = loginPageAfterFailure(failure);
        assertThat(page).contains(INVALID_MESSAGE).contains("value=\"admin02\"");
        assertThat(page).doesNotContain("wrong-password-9");
        assertThat(lastLoginAt("admin02")).isNull();
    }

    @Test
    @DisplayName("FNC-C-001 bcrypt 한도(72바이트)를 넘는 비밀번호는 500이 아니라 일반 실패 문구로 거부한다")
    void login_passwordOverBcryptLimit_rejectedWithGenericMessage() throws Exception {
        insertAccount("admin05", "ADMIN", "ACTIVE", false);
        // 한글 30자 = 90바이트
        String longPassword = "가".repeat(30);

        MvcResult failure = login("admin05", longPassword);

        assertThat(failure.getResponse().getStatus()).isEqualTo(302);
        assertThat(failure.getResponse().getRedirectedUrl()).isEqualTo("/admin/login?error");
        assertThat(loginPageAfterFailure(failure)).contains(INVALID_MESSAGE);
        assertThat(lastLoginAt("admin05")).isNull();
    }

    @Test
    @DisplayName("FNC-C-001 이 앱의 계정 테이블에 없는 아이디(다른 앱 계정 포함)는 틀린 비밀번호와 같은 문구로 거부한다")
    void login_unknownAccount_sameMessage() throws Exception {
        MvcResult failure = login("only-in-user-app", PASSWORD);

        assertThat(failure.getResponse().getRedirectedUrl()).isEqualTo("/admin/login?error");
        assertThat(loginPageAfterFailure(failure)).contains(INVALID_MESSAGE);
    }

    @Test
    @DisplayName("FNC-C-001 로그인에 성공하면 마지막 로그인 시각이 기록된다")
    void login_success_recordsLastLoginAt() throws Exception {
        insertAccount("admin03", "ADMIN", "ACTIVE", false);
        assertThat(lastLoginAt("admin03")).isNull();

        login("admin03", PASSWORD);

        assertThat(lastLoginAt("admin03")).isNotNull();
    }

    @Test
    @DisplayName("FNC-C-001 관리자 세션은 SPRING_SESSION_ADMIN에만 로그인 아이디로 저장되고 사용자 세션 테이블에는 쓰지 않는다")
    void login_success_sessionStoredInAdminTableOnly() throws Exception {
        insertAccount("admin04", "ADMIN", "ACTIVE", false);
        int userSessionsBefore = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM SPRING_SESSION_USER", Integer.class);

        login("admin04", PASSWORD);

        Integer adminSessions = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM SPRING_SESSION_ADMIN WHERE PRINCIPAL_NAME = ?", Integer.class, "admin04");
        assertThat(adminSessions).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM SPRING_SESSION_USER", Integer.class))
                .isEqualTo(userSessionsBefore);
    }

    @Test
    @DisplayName("FNC-C-001 CSRF 토큰 없이 로그인 요청을 보내면 거부한다")
    void login_withoutCsrf_forbidden() throws Exception {
        insertAccount("admin05", "ADMIN", "ACTIVE", false);

        mockMvc.perform(post("/admin/login")
                        .contextPath(CONTEXT_PATH)
                        .param("username", "admin05")
                        .param("password", PASSWORD))
                .andExpect(status().isForbidden());
        assertThat(lastLoginAt("admin05")).isNull();
    }

    @Test
    @DisplayName("SCR-C-001 로그인 화면에 아이디·비밀번호 칸과 CSRF 토큰이 있다")
    void loginPage_rendersFormWithCsrf() throws Exception {
        mockMvc.perform(get("/admin/login").contextPath(CONTEXT_PATH))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("name=\"username\"")))
                .andExpect(content().string(containsString("maxlength=\"20\"")))
                .andExpect(content().string(containsString("type=\"password\"")))
                .andExpect(content().string(containsString("name=\"_csrf\"")))
                .andExpect(content().string(containsString("action=\"/admin/login\"")));
    }

    @Test
    @DisplayName("SCR-C-001 이미 로그인한 상태로 로그인 화면에 들어오면 통계 메인으로 이동한다")
    void loginPage_alreadyLoggedIn_redirectsToStats() throws Exception {
        insertAccount("admin06", "ADMIN", "ACTIVE", false);
        Cookie session = login("admin06", PASSWORD).getResponse().getCookie(SESSION_COOKIE);

        mockMvc.perform(get("/admin/login").contextPath(CONTEXT_PATH).cookie(session))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/admin/stats"));
    }
}
