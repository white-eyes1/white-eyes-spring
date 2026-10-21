package com.whiteeyes.user.account.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import com.whiteeyes.common.account.entity.UserAccountStatus;

@SpringBootTest
@Transactional
class SignupServiceTest {

    private static final String LOGIN_ID = "hong01";

    private final SignupService signupService;
    private final JdbcTemplate jdbcTemplate;

    SignupServiceTest(@Autowired SignupService signupService, @Autowired JdbcTemplate jdbcTemplate) {
        this.signupService = signupService;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Test
    @DisplayName("FNC-U-001 탈퇴 계정의 아이디는 사용할 수 없다")
    void isLoginIdAvailable_withdrawnAccount_false() {
        givenAccount(LOGIN_ID, UserAccountStatus.WITHDRAWN);

        assertThat(signupService.isLoginIdAvailable(LOGIN_ID)).isFalse();
    }

    @Test
    @DisplayName("FNC-U-001 미승인 계정의 아이디는 사용할 수 없다")
    void isLoginIdAvailable_pendingAccount_false() {
        givenAccount(LOGIN_ID, UserAccountStatus.PENDING);

        assertThat(signupService.isLoginIdAvailable(LOGIN_ID)).isFalse();
    }

    @Test
    @DisplayName("FNC-U-001 정상 계정의 아이디는 사용할 수 없다")
    void isLoginIdAvailable_activeAccount_false() {
        givenAccount(LOGIN_ID, UserAccountStatus.ACTIVE);

        assertThat(signupService.isLoginIdAvailable(LOGIN_ID)).isFalse();
    }

    @Test
    @DisplayName("FNC-U-001 없는 아이디는 사용할 수 있다")
    void isLoginIdAvailable_noAccount_true() {
        givenAccount("other01", UserAccountStatus.ACTIVE);

        assertThat(signupService.isLoginIdAvailable(LOGIN_ID)).isTrue();
    }

    // 엔티티에 생성자를 열지 않으려고 테스트 행은 SQL로 넣는다
    private void givenAccount(String loginId, UserAccountStatus status) {
        jdbcTemplate.update(
                "INSERT INTO user_account (login_id, password, name, status, must_change_password, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                loginId,
                "$2a$10$abcdefghijklmnopqrstuuabcdefghijklmnopqrstuvwxyz01234",
                "홍길동",
                status.name(),
                false);
    }
}
