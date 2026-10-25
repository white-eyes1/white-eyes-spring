package com.whiteeyes.user.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.whiteeyes.common.account.repository.UserAccountRepository;
import com.whiteeyes.user.support.UserTestAccounts;
import jakarta.servlet.http.Cookie;
import java.time.LocalDateTime;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
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
 * 회원 탈퇴(FNC-U-004): 비밀번호 확인, WITHDRAWN·withdrawn_at, 세션 삭제, 검수 기준(탈퇴 후 로그인 거부, 재가입 시 아이디 중복).
 * 실제로 로그인(POST /login)한 세션으로 확인한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class UserWithdrawTest {

    private static final String ME_URL = "/api/v1/me";

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
    @DisplayName("FNC-U-004 비밀번호가 맞으면 204이고 status=WITHDRAWN, withdrawn_at이 채워지며 행은 남는다")
    void withdraw_marksWithdrawn() throws Exception {
        Cookie session = accounts.loginAs("bye01", "ACTIVE", false);
        LocalDateTime before = LocalDateTime.now().minusMinutes(1);

        withdraw(session, UserTestAccounts.PASSWORD).andExpect(status().isNoContent());

        Map<String, Object> row = jdbcTemplate.queryForMap("SELECT * FROM user_account WHERE login_id = 'bye01'");
        assertThat(row.get("STATUS")).isEqualTo("WITHDRAWN");
        assertThat(((java.sql.Timestamp) row.get("WITHDRAWN_AT")).toLocalDateTime()).isAfter(before);
    }

    @Test
    @DisplayName("FNC-U-004 이 계정의 모든 세션을 지운다. 탈퇴한 브라우저의 다음 요청은 로그인 화면으로, 다른 계정 세션은 그대로다")
    void withdraw_deletesAllSessions() throws Exception {
        Cookie session = accounts.loginAs("bye01", "ACTIVE", false);
        Cookie otherBrowser = accounts.login("bye01", UserTestAccounts.PASSWORD).getResponse().getCookie("SESSION");
        Cookie someoneElse = accounts.loginAs("stay02", "ACTIVE", false);
        assertThat(sessionsOf("bye01")).isEqualTo(2);

        withdraw(session, UserTestAccounts.PASSWORD).andExpect(status().isNoContent());

        assertThat(sessionsOf("bye01")).as("지금 세션과 다른 브라우저 세션 모두").isZero();
        mockMvc.perform(get("/stats").cookie(session)).andExpect(redirectedUrl("/login"));
        mockMvc.perform(get("/stats").cookie(otherBrowser)).andExpect(redirectedUrl("/login"));
        mockMvc.perform(get("/stats").cookie(someoneElse)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("FNC-U-004 검수 기준: 탈퇴 후 같은 아이디·비밀번호로 로그인하면 거부된다")
    void afterWithdraw_loginRejected() throws Exception {
        Cookie session = accounts.loginAs("bye01", "ACTIVE", false);
        withdraw(session, UserTestAccounts.PASSWORD).andExpect(status().isNoContent());

        var result = accounts.login("bye01", UserTestAccounts.PASSWORD);

        assertThat(result.getResponse().getRedirectedUrl()).isEqualTo("/login?error");
        assertThat(sessionsOf("bye01")).isZero();
    }

    @Test
    @DisplayName("FNC-U-004 검수 기준: 탈퇴한 아이디로 다시 가입하면 아이디 중복(확인은 사용 불가, 가입은 409)")
    void afterWithdraw_signupDuplicate() throws Exception {
        Cookie session = accounts.loginAs("bye01", "ACTIVE", false);
        withdraw(session, UserTestAccounts.PASSWORD).andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/signup/check-id").param("loginId", "bye01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(false));
        mockMvc.perform(post("/api/v1/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"loginId":"bye01","password":"abcd1234","passwordConfirm":"abcd1234","name":"다시"}""")
                        .with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_LOGIN_ID"));
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM user_account WHERE login_id = 'bye01'", Long.class))
                .isOne();
    }

    @Test
    @DisplayName("FNC-U-004 비밀번호가 틀리면 400 WRONG_PASSWORD이고 상태·세션이 그대로다")
    void wrongPassword_badRequest() throws Exception {
        Cookie session = accounts.loginAs("bye01", "ACTIVE", false);

        withdraw(session, "wrong-password-9")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("WRONG_PASSWORD"));

        assertThat(jdbcTemplate.queryForObject(
                        "SELECT status FROM user_account WHERE login_id = 'bye01'", String.class))
                .isEqualTo("ACTIVE");
        mockMvc.perform(get("/stats").cookie(session)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("FNC-U-004 비밀번호를 비우면 400 INVALID_INPUT(password 칸 오류)")
    void blankPassword_invalidInput() throws Exception {
        Cookie session = accounts.loginAs("bye01", "ACTIVE", false);

        withdraw(session, "")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("password"));
    }

    @Test
    @DisplayName("FNC-U-004 로그인하지 않은 요청은 401")
    void anonymous_unauthorized() throws Exception {
        accounts.insert("bye01", "ACTIVE", false);

        mockMvc.perform(delete(ME_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"test-password-1!\"}")
                        .with(csrf()))
                .andExpect(status().isUnauthorized());

        assertThat(jdbcTemplate.queryForObject(
                        "SELECT status FROM user_account WHERE login_id = 'bye01'", String.class))
                .isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("SCR-C-002·SCR-U-003 사용자 앱 /me에 회원 탈퇴 버튼, 탈퇴 창(안내·비밀번호·처음엔 꺼진 탈퇴 버튼), 확인 창이 있다")
    void mePage_hasWithdraw() throws Exception {
        Cookie session = accounts.loginAs("bye01", "ACTIVE", false);

        String page = mockMvc.perform(get("/me").cookie(session))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);

        assertThat(page)
                .contains("id=\"withdraw-open\"")
                .contains("id=\"withdraw-modal\" hidden")
                .contains("탈퇴하면 같은 아이디로 다시 가입할 수 없습니다.")
                .contains("id=\"withdraw-password\" name=\"password\" type=\"password\"")
                .contains("type=\"submit\" id=\"withdraw-submit\" disabled>탈퇴</button>")
                .contains("id=\"withdraw-confirm-modal\" hidden")
                .contains("/js/user/withdraw.js")
                .contains("/css/user/withdraw.css");
        mockMvc.perform(get("/js/user/withdraw.js")).andExpect(status().isOk());
        mockMvc.perform(get("/css/user/withdraw.css")).andExpect(status().isOk());
    }

    private ResultActions withdraw(Cookie session, String password) throws Exception {
        return mockMvc.perform(delete(ME_URL)
                .cookie(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"password\":\"" + password + "\"}")
                .with(csrf()));
    }

    private long sessionsOf(String loginId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM SPRING_SESSION WHERE PRINCIPAL_NAME = ?", Long.class, loginId);
    }
}
