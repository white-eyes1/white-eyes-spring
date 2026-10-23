package com.whiteeyes.user.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.whiteeyes.common.account.entity.UserAccount;
import com.whiteeyes.common.account.repository.UserAccountRepository;
import jakarta.servlet.http.Cookie;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 사용자 앱 권한 시험에서 user_account에 계정을 넣고 실제 로그인(POST /login)하는 도구.
 * 사용자 앱은 Flyway를 돌리지 않아 테스트 H2의 세션 표는 Spring Session 기본 이름(SPRING_SESSION)이고,
 * MockMvc에는 내장 서버가 없어 쿠키 이름도 기본값(SESSION)이다.
 */
public final class UserTestAccounts {

    public static final String PASSWORD = "test-password-1!";
    public static final String SESSION_COOKIE = "SESSION";

    private final MockMvc mockMvc;
    private final JdbcTemplate jdbcTemplate;
    private final UserAccountRepository userAccountRepository;
    private final PasswordEncoder passwordEncoder;

    public UserTestAccounts(
            MockMvc mockMvc,
            JdbcTemplate jdbcTemplate,
            UserAccountRepository userAccountRepository,
            PasswordEncoder passwordEncoder) {
        this.mockMvc = mockMvc;
        this.jdbcTemplate = jdbcTemplate;
        this.userAccountRepository = userAccountRepository;
        this.passwordEncoder = passwordEncoder;
    }

    /** 세션과 계정을 모두 지운다. */
    public void clear() {
        jdbcTemplate.update("DELETE FROM SPRING_SESSION_ATTRIBUTES");
        jdbcTemplate.update("DELETE FROM SPRING_SESSION");
        userAccountRepository.deleteAll();
    }

    /** 비밀번호가 PASSWORD인 계정을 넣고 계정 번호를 돌려준다. status는 PENDING·ACTIVE·WITHDRAWN. */
    public Long insert(String loginId, String status, boolean mustChangePassword) {
        UserAccount account = UserAccount.createPending(loginId, passwordEncoder.encode(PASSWORD), "테스트 사용자", null, null);
        Long id = userAccountRepository.save(account).getId();
        jdbcTemplate.update(
                "UPDATE user_account SET status = ?, must_change_password = ? WHERE id = ?",
                status,
                mustChangePassword,
                id);
        return id;
    }

    /** 로그인 화면의 폼처럼 POST /login을 보낸다. */
    public MvcResult login(String loginId, String password) throws Exception {
        return mockMvc.perform(post("/login")
                        .param("username", loginId)
                        .param("password", password)
                        .with(csrf()))
                .andReturn();
    }

    /** 계정을 넣고 로그인해 받은 세션 쿠키를 돌려준다. */
    public Cookie loginAs(String loginId, String status, boolean mustChangePassword) throws Exception {
        insert(loginId, status, mustChangePassword);
        Cookie session = login(loginId, PASSWORD).getResponse().getCookie(SESSION_COOKIE);
        assertThat(session).as("로그인 뒤 세션 쿠키").isNotNull();
        return session;
    }
}
