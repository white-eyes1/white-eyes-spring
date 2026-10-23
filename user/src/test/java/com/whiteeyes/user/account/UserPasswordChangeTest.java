package com.whiteeyes.user.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.whiteeyes.common.account.entity.UserAccount;
import com.whiteeyes.common.account.repository.UserAccountRepository;
import com.whiteeyes.common.security.AccountPrincipal;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 사용자 앱 비밀번호 변경(FNC-C-007). 사용자 앱 로그인이 아직 없어(이슈 #126) 세션을 저장소에 직접 만들어 확인한다.
 * 비밀번호 변경이 필요한 계정을 변경 화면에 가두는 필터 연결도 #126이라 여기서는 API 동작만 본다.
 */
// 사용자 앱은 Flyway를 돌리지 않아 테스트 H2의 세션 표는 Spring Session이 기본 이름(SPRING_SESSION)으로 만든다
@SpringBootTest
@AutoConfigureMockMvc
class UserPasswordChangeTest {

    private static final String SESSION_COOKIE = "SESSION";
    private static final String SECURITY_CONTEXT = "SPRING_SECURITY_CONTEXT";
    private static final String LOGIN_ID = "user01";
    private static final String CURRENT = "test-password-1!";
    private static final String NEW = "new-password-2@";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JdbcIndexedSessionRepository sessionRepository;

    @Autowired
    private UserAccountRepository userAccountRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private Long accountId;

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM SPRING_SESSION_ATTRIBUTES");
        jdbcTemplate.update("DELETE FROM SPRING_SESSION");
        userAccountRepository.deleteAll();
    }

    @BeforeEach
    void insertAccount() {
        cleanUp();
        UserAccount account = UserAccount.createPending(LOGIN_ID, passwordEncoder.encode(CURRENT), "테스트 사용자", null, null);
        accountId = userAccountRepository.save(account).getId();
        jdbcTemplate.update(
                "UPDATE user_account SET status = 'ACTIVE', must_change_password = TRUE WHERE id = ?", accountId);
    }

    // 로그인한 것처럼 세션을 저장소에 직접 만들고, 브라우저가 보낼 쿠키(세션 번호 Base64)를 돌려준다
    @SuppressWarnings("unchecked")
    private Cookie sessionCookie(boolean mustChangePassword) {
        var authorities = List.of(new SimpleGrantedAuthority("ROLE_USER"));
        var principal = new AccountPrincipal(accountId, LOGIN_ID, "encoded", true, mustChangePassword, authorities);
        var authentication = UsernamePasswordAuthenticationToken.authenticated(principal, null, authorities);
        Session session = sessionRepository.createSession();
        session.setAttribute(SECURITY_CONTEXT, new SecurityContextImpl(authentication));
        ((SessionRepository<Session>) (SessionRepository<?>) sessionRepository).save(session);
        return new Cookie(
                SESSION_COOKIE, Base64.getEncoder().encodeToString(session.getId().getBytes(StandardCharsets.UTF_8)));
    }

    private String sessionId(Cookie cookie) {
        return new String(Base64.getDecoder().decode(cookie.getValue()), StandardCharsets.UTF_8);
    }

    private ResultActions changePassword(Cookie session, String current, String newPassword, String confirm)
            throws Exception {
        String body = """
                {"currentPassword":"%s","newPassword":"%s","newPasswordConfirm":"%s"}"""
                .formatted(current, newPassword, confirm);
        return mockMvc.perform(patch("/api/v1/me/password")
                .cookie(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private String storedPassword() {
        return jdbcTemplate.queryForObject("SELECT password FROM user_account WHERE id = ?", String.class, accountId);
    }

    private long sessionRows() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM SPRING_SESSION", Long.class);
    }

    @Test
    @DisplayName("FNC-C-007 사용자 앱: 현재 비밀번호가 맞으면 새 비밀번호로 바뀌고 must_change_password가 꺼진다")
    void change_updatesPasswordAndFlag() throws Exception {
        Cookie session = sessionCookie(true);

        changePassword(session, CURRENT, NEW, NEW).andExpect(status().isNoContent());

        assertThat(passwordEncoder.matches(NEW, storedPassword())).isTrue();
        assertThat(userAccountRepository.findById(accountId).orElseThrow().isMustChangePassword())
                .isFalse();
    }

    @Test
    @DisplayName("FNC-C-007 사용자 앱: 다른 세션은 지우고 현재 세션의 로그인 정보는 must_change_password=false로 갱신한다")
    void change_endsOtherSessions_refreshesCurrentSession() throws Exception {
        Cookie changing = sessionCookie(true);
        sessionCookie(false);
        assertThat(sessionRows()).isEqualTo(2);

        changePassword(changing, CURRENT, NEW, NEW).andExpect(status().isNoContent());

        assertThat(sessionRows()).isEqualTo(1);
        Session kept = sessionRepository.findById(sessionId(changing));
        assertThat(kept).isNotNull();
        SecurityContext context = kept.getAttribute(SECURITY_CONTEXT);
        assertThat(context.getAuthentication().getPrincipal())
                .isInstanceOfSatisfying(AccountPrincipal.class, principal -> {
                    assertThat(principal.isMustChangePassword()).isFalse();
                    assertThat(principal.getAccountId()).isEqualTo(accountId);
                });
    }

    @Test
    @DisplayName("FNC-C-007 사용자 앱: 현재 비밀번호가 틀리면 400 WRONG_PASSWORD이고 아무것도 바뀌지 않는다")
    void wrongCurrent_rejected() throws Exception {
        Cookie session = sessionCookie(true);
        sessionCookie(false);
        String before = storedPassword();

        changePassword(session, "wrong-pass9!", NEW, NEW)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("WRONG_PASSWORD"));

        assertThat(storedPassword()).isEqualTo(before);
        assertThat(sessionRows()).isEqualTo(2);
    }

    @Test
    @DisplayName("FNC-C-007 사용자 앱: 규칙 위반(한글 포함)은 400이고 칸별 오류가 담긴다")
    void invalidInput_fieldErrors() throws Exception {
        Cookie session = sessionCookie(false);

        changePassword(session, CURRENT, "비밀번호abc1234", "비밀번호abc1234")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("newPassword"));

        assertThat(passwordEncoder.matches(CURRENT, storedPassword())).isTrue();
    }

    @Test
    @DisplayName("FNC-C-007 사용자 앱: 다른 계정의 세션은 건드리지 않는다")
    void change_keepsOtherAccountsSessions() throws Exception {
        Cookie changing = sessionCookie(false);
        Long otherId = userAccountRepository
                .save(UserAccount.createPending("user02", passwordEncoder.encode(CURRENT), "다른 사용자", null, null))
                .getId();
        var authorities = List.of(new SimpleGrantedAuthority("ROLE_USER"));
        var principal = new AccountPrincipal(otherId, "user02", "encoded", true, false, authorities);
        Session otherSession = sessionRepository.createSession();
        otherSession.setAttribute(
                SECURITY_CONTEXT,
                new SecurityContextImpl(UsernamePasswordAuthenticationToken.authenticated(principal, null, authorities)));
        @SuppressWarnings("unchecked")
        SessionRepository<Session> repository = (SessionRepository<Session>) (SessionRepository<?>) sessionRepository;
        repository.save(otherSession);

        changePassword(changing, CURRENT, NEW, NEW).andExpect(status().isNoContent());

        assertThat(sessionRepository.findById(otherSession.getId())).isNotNull();
        assertThat(sessionRows()).isEqualTo(2);
    }

    @Test
    @DisplayName("FNC-C-007 사용자 앱: 변경 화면은 강제 상태에서만 안내 문구를 보이고 메뉴를 숨긴다")
    void passwordPage_forcedHidesMenu() throws Exception {
        mockMvc.perform(get("/me/password").cookie(sessionCookie(true)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("임시 비밀번호로 로그인했습니다.")))
                .andExpect(content().string(containsString("data-header=\"forced\"")))
                .andExpect(content().string(not(containsString("data-header=\"user\""))));
        mockMvc.perform(get("/me/password").cookie(sessionCookie(false)))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("임시 비밀번호로 로그인했습니다."))))
                .andExpect(content().string(containsString("data-header=\"user\"")))
                .andExpect(content().string(not(containsString("data-header=\"forced\""))));
    }

    @Test
    @DisplayName("FNC-C-007 사용자 앱: 로그인하지 않은 변경 화면 요청은 401이다(로그인 화면 이동은 #126)")
    void passwordPage_notLoggedIn_unauthorized() throws Exception {
        mockMvc.perform(get("/me/password")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("FNC-C-007 사용자 앱: 로그인하지 않았으면 401이다")
    void notLoggedIn_unauthorized() throws Exception {
        mockMvc.perform(patch("/api/v1/me/password")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"%s\",\"newPassword\":\"%s\",\"newPasswordConfirm\":\"%s\"}"
                                .formatted(CURRENT, NEW, NEW)))
                .andExpect(status().isUnauthorized());
    }
}
