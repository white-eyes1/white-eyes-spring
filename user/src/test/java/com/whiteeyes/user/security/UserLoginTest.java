package com.whiteeyes.user.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.whiteeyes.common.account.repository.UserAccountRepository;
import com.whiteeyes.user.support.UserTestAccounts;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
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
 * 사용자 앱 로그인(FNC-C-001). 검수 기준: 사용자 앱에서 각 상태·역할 조합별 허용/거부(PENDING은 /pending으로만, FNC-U-003).
 * user_account에 계정을 넣고 실제로 POST /login 한 세션으로 확인한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class UserLoginTest {

    private static final String PASSWORD = UserTestAccounts.PASSWORD;
    private static final String INVALID_MESSAGE = "아이디 또는 비밀번호가 올바르지 않습니다.";
    private static final String DISABLED_MESSAGE = "사용이 중지된 계정입니다.";

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

    private String loginPageAfterFailure(MvcResult failure) throws Exception {
        Cookie session = failure.getResponse().getCookie(UserTestAccounts.SESSION_COOKIE);
        assertThat(session).as("실패해도 오류 문구를 담을 세션 쿠키").isNotNull();
        return mockMvc.perform(get("/login").cookie(session))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    private LocalDateTime lastLoginAt(String loginId) {
        return jdbcTemplate.queryForObject(
                "SELECT last_login_at FROM user_account WHERE login_id = ?", LocalDateTime.class, loginId);
    }

    @Test
    @DisplayName("FNC-C-001 ACTIVE 사용자는 로그인하고 통계 메인으로 이동한다")
    void login_active_redirectsToStats() throws Exception {
        accounts.insert("user01", "ACTIVE", false);

        mockMvc.perform(post("/login").param("username", "user01").param("password", PASSWORD).with(csrf()))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/stats"))
                .andExpect(cookie().exists(UserTestAccounts.SESSION_COOKIE));
    }

    @Test
    @DisplayName("FNC-C-001 ACTIVE 사용자의 세션은 ROLE_USER라 사용자 이상 API를 쓸 수 있고 승인 대기 화면은 403이다")
    void login_active_hasUserRole() throws Exception {
        Cookie session = accounts.loginAs("user02", "ACTIVE", false);

        mockMvc.perform(get("/api/v1/meta/levels").cookie(session)).andExpect(status().isOk());
        mockMvc.perform(get("/pending").cookie(session)).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("FNC-U-003 PENDING 계정은 실제로 로그인하면 승인 대기 화면으로 가고 그 화면을 볼 수 있다")
    void login_pending_redirectsToPendingPage() throws Exception {
        accounts.insert("wait01", "PENDING", false);

        MvcResult login = accounts.login("wait01", PASSWORD);

        assertThat(login.getResponse().getRedirectedUrl()).isEqualTo("/pending");
        Cookie session = login.getResponse().getCookie(UserTestAccounts.SESSION_COOKIE);
        mockMvc.perform(get("/pending").cookie(session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("관리자 승인을 기다리고 있습니다")));
    }

    @Test
    @DisplayName("FNC-U-003 로그인한 PENDING 계정의 다른 화면 요청은 /pending으로, API는 403이다")
    void login_pending_onlyPendingPage() throws Exception {
        Cookie session = accounts.loginAs("wait02", "PENDING", false);

        mockMvc.perform(get("/stats").cookie(session))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/pending"));
        mockMvc.perform(get("/me/password").cookie(session))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/pending"));
        mockMvc.perform(get("/api/v1/meta/levels").cookie(session))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mockMvc.perform(get("/api/v1/regions").cookie(session))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("FNC-U-003 로그인한 PENDING 계정도 로그아웃할 수 있다")
    void login_pending_canLogout() throws Exception {
        Cookie session = accounts.loginAs("wait03", "PENDING", false);

        mockMvc.perform(post("/logout").cookie(session).with(csrf()))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/login"));
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM SPRING_SESSION", Long.class)).isZero();
    }

    @Test
    @DisplayName("FNC-C-001 must_change_password=1이면 비밀번호 변경 화면으로 이동한다")
    void login_mustChangePassword_redirectsToPasswordChange() throws Exception {
        accounts.insert("temp01", "ACTIVE", true);

        assertThat(accounts.login("temp01", PASSWORD).getResponse().getRedirectedUrl()).isEqualTo("/me/password");
    }

    @Test
    @DisplayName("FNC-C-003 비밀번호 변경이 필요한 계정은 변경 화면만 쓰고, 다른 화면은 변경 화면으로·API는 403이다")
    void mustChangePassword_onlyPasswordChange() throws Exception {
        Cookie session = accounts.loginAs("temp02", "ACTIVE", true);

        mockMvc.perform(get("/me/password").cookie(session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("임시 비밀번호로 로그인했습니다.")));
        mockMvc.perform(get("/stats").cookie(session))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/me/password"));
        mockMvc.perform(get("/api/v1/meta/levels").cookie(session))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("FNC-C-001 WITHDRAWN 계정은 비밀번호가 맞아도 틀린 비밀번호와 같은 문구로 거부한다")
    void login_withdrawn_rejectedWithInvalidMessage() throws Exception {
        accounts.insert("gone01", "WITHDRAWN", false);

        MvcResult failure = accounts.login("gone01", PASSWORD);

        assertThat(failure.getResponse().getRedirectedUrl()).isEqualTo("/login?error");
        assertThat(loginPageAfterFailure(failure)).contains(INVALID_MESSAGE).doesNotContain(DISABLED_MESSAGE);
        assertThat(lastLoginAt("gone01")).isNull();
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM SPRING_SESSION WHERE PRINCIPAL_NAME = 'gone01'", Long.class))
                .isZero();
    }

    @Test
    @DisplayName("FNC-C-001 틀린 비밀번호는 하나의 문구로 거부하고 입력한 아이디를 유지한다")
    void login_wrongPassword_rejectedAndKeepsLoginId() throws Exception {
        accounts.insert("user03", "ACTIVE", false);

        MvcResult failure = accounts.login("user03", "wrong-password-9");

        assertThat(failure.getResponse().getRedirectedUrl()).isEqualTo("/login?error");
        String page = loginPageAfterFailure(failure);
        assertThat(page).contains(INVALID_MESSAGE).contains("value=\"user03\"").doesNotContain("wrong-password-9");
        assertThat(lastLoginAt("user03")).isNull();
    }

    @Test
    @DisplayName("FNC-C-001 PENDING 계정에 틀린 비밀번호를 보내도 같은 문구로 거부한다")
    void login_pendingWrongPassword_sameMessage() throws Exception {
        accounts.insert("wait04", "PENDING", false);

        MvcResult failure = accounts.login("wait04", "wrong-password-9");

        assertThat(failure.getResponse().getRedirectedUrl()).isEqualTo("/login?error");
        assertThat(loginPageAfterFailure(failure)).contains(INVALID_MESSAGE);
    }

    @Test
    @DisplayName("FNC-C-001 user_account에 없는 아이디(관리자 앱 계정 포함)는 틀린 비밀번호와 같은 문구로 거부한다")
    void login_unknownAccount_sameMessage() throws Exception {
        MvcResult failure = accounts.login("only-in-admin-app", PASSWORD);

        assertThat(failure.getResponse().getRedirectedUrl()).isEqualTo("/login?error");
        assertThat(loginPageAfterFailure(failure)).contains(INVALID_MESSAGE);
    }

    @Test
    @DisplayName("FNC-C-001 로그인에 성공하면 마지막 로그인 시각이 기록된다(PENDING 포함)")
    void login_success_recordsLastLoginAt() throws Exception {
        accounts.insert("user04", "ACTIVE", false);
        accounts.insert("wait05", "PENDING", false);

        accounts.login("user04", PASSWORD);
        accounts.login("wait05", PASSWORD);

        assertThat(lastLoginAt("user04")).isNotNull();
        assertThat(lastLoginAt("wait05")).isNotNull();
    }

    @Test
    @DisplayName("FNC-C-001 사용자 세션은 사용자 앱 세션 표에 로그인 아이디로 저장되고 비밀번호 해시는 담기지 않는다")
    void login_success_sessionStoredByLoginId() throws Exception {
        accounts.loginAs("user05", "ACTIVE", false);

        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM SPRING_SESSION WHERE PRINCIPAL_NAME = 'user05'", Long.class))
                .isEqualTo(1L);
        String stored = new String(
                jdbcTemplate.queryForObject(
                        "SELECT ATTRIBUTE_BYTES FROM SPRING_SESSION_ATTRIBUTES"
                                + " WHERE ATTRIBUTE_NAME = 'SPRING_SECURITY_CONTEXT'",
                        byte[].class),
                StandardCharsets.ISO_8859_1);
        assertThat(stored).contains("ROLE_USER").doesNotContain("$2a$");
    }

    @Test
    @DisplayName("FNC-C-001 CSRF 토큰 없이 로그인 요청을 보내면 거부한다")
    void login_withoutCsrf_forbidden() throws Exception {
        accounts.insert("user06", "ACTIVE", false);

        mockMvc.perform(post("/login").param("username", "user06").param("password", PASSWORD))
                .andExpect(status().isForbidden());
        assertThat(lastLoginAt("user06")).isNull();
    }

    @Test
    @DisplayName("SCR-C-001 사용자 앱 로그인 화면은 공통 화면 그대로 POST /login 폼과 CSRF 토큰을 보인다")
    void loginPage_rendersFormWithCsrf() throws Exception {
        mockMvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("name=\"username\"")))
                .andExpect(content().string(containsString("type=\"password\"")))
                .andExpect(content().string(containsString("name=\"_csrf\"")))
                .andExpect(content().string(containsString("action=\"/login\"")));
    }

    @Test
    @DisplayName("SCR-C-001 사용자 앱 로그인 화면에는 회원가입 화면으로 가는 링크가 있다")
    void loginPage_showsSignupLink() throws Exception {
        mockMvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("href=\"/signup\"")))
                .andExpect(content().string(containsString("회원가입")));
    }

    @Test
    @DisplayName("SCR-U-001 가입을 마치고 넘어온 로그인 화면(?signup=done)에는 가입 완료 안내가 화면 안에 보인다")
    void loginPage_afterSignup_showsDoneNotice() throws Exception {
        mockMvc.perform(get("/login").param("signup", "done"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("가입 신청이 완료되었습니다. 관리자 승인 후 이용할 수 있습니다.")));
    }

    @Test
    @DisplayName("SCR-U-001 그냥 연 로그인 화면이나 다른 값(?signup=x)에는 가입 완료 안내가 없다")
    void loginPage_withoutSignupDone_hidesDoneNotice() throws Exception {
        mockMvc.perform(get("/login"))
                .andExpect(content().string(not(containsString("가입 신청이 완료되었습니다"))));
        mockMvc.perform(get("/login").param("signup", "x"))
                .andExpect(content().string(not(containsString("가입 신청이 완료되었습니다"))));
    }

    @Test
    @DisplayName("SCR-C-001 이미 로그인한 상태로 로그인 화면에 들어오면 통계 메인으로 이동한다")
    void loginPage_alreadyLoggedIn_redirectsToStats() throws Exception {
        Cookie session = accounts.loginAs("user07", "ACTIVE", false);

        mockMvc.perform(get("/login").cookie(session))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/stats"));
    }
}
