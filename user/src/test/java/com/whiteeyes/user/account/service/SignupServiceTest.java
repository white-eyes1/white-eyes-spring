package com.whiteeyes.user.account.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.willReturn;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Transactional;

import com.whiteeyes.common.account.entity.UserAccountStatus;
import com.whiteeyes.common.account.repository.UserAccountRepository;
import com.whiteeyes.user.account.dto.SignupRequest;
import com.whiteeyes.user.account.dto.SignupResponse;
import com.whiteeyes.user.account.exception.DuplicateLoginIdException;

@SpringBootTest
@Transactional
class SignupServiceTest {

    private static final String LOGIN_ID = "hong01";

    private final SignupService signupService;
    private final JdbcTemplate jdbcTemplate;
    private final PasswordEncoder passwordEncoder;

    // 미리 확인(existsByLoginId)을 건너뛰게 해서 DB 유일 조건이 막는 경로를 확인하는 데 쓴다
    @MockitoSpyBean
    private UserAccountRepository userAccountRepository;

    SignupServiceTest(
            @Autowired SignupService signupService,
            @Autowired JdbcTemplate jdbcTemplate,
            @Autowired PasswordEncoder passwordEncoder) {
        this.signupService = signupService;
        this.jdbcTemplate = jdbcTemplate;
        this.passwordEncoder = passwordEncoder;
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

    @Test
    @DisplayName("FNC-U-002 가입한 계정은 PENDING, 비밀번호는 bcrypt로 저장")
    void signup_savesPendingWithBcrypt() {
        SignupResponse response = signupService.signup(request(LOGIN_ID, "a@b.com", "010-1234-5678"));

        assertThat(response.status()).isEqualTo(UserAccountStatus.PENDING);
        Map<String, Object> row = findRow(response.id());
        assertThat(row.get("login_id")).isEqualTo(LOGIN_ID);
        assertThat(row.get("status")).isEqualTo("PENDING");
        assertThat(row.get("must_change_password")).isEqualTo(false);
        assertThat(row.get("name")).isEqualTo("홍길동");
        assertThat(row.get("email")).isEqualTo("a@b.com");
        assertThat(row.get("phone")).isEqualTo("010-1234-5678");
        String password = (String) row.get("password");
        assertThat(password).hasSize(60).isNotEqualTo("abcd1234");
        assertThat(passwordEncoder.matches("abcd1234", password)).isTrue();
    }

    @Test
    @DisplayName("FNC-U-002 비워 보낸 메일·연락처는 NULL로 저장")
    void signup_emptyOptional_savedAsNull() {
        SignupResponse response = signupService.signup(request(LOGIN_ID, "", ""));

        Map<String, Object> row = findRow(response.id());
        assertThat(row.get("email")).isNull();
        assertThat(row.get("phone")).isNull();
    }

    @Test
    @DisplayName("FNC-U-002 탈퇴 계정의 아이디로는 가입할 수 없다")
    void signup_withdrawnLoginId_duplicate() {
        givenAccount(LOGIN_ID, UserAccountStatus.WITHDRAWN);

        assertThatThrownBy(() -> signupService.signup(request(LOGIN_ID, null, null)))
                .isInstanceOf(DuplicateLoginIdException.class);
        assertThat(countRows(LOGIN_ID)).isEqualTo(1);
    }

    @Test
    @DisplayName("FNC-U-002 미리 확인을 통과해도 DB 유일 조건이 막아 아이디 중복으로 응답")
    void signup_uniqueConstraint_duplicate() {
        givenAccount(LOGIN_ID, UserAccountStatus.ACTIVE);
        // 동시 가입에서 두 요청이 모두 미리 확인을 통과한 상황을 만든다
        willReturn(false).given(userAccountRepository).existsByLoginId(LOGIN_ID);

        assertThatThrownBy(() -> signupService.signup(request(LOGIN_ID, null, null)))
                .isInstanceOf(DuplicateLoginIdException.class)
                .hasCauseInstanceOf(DataIntegrityViolationException.class);
    }

    private static SignupRequest request(String loginId, String email, String phone) {
        return new SignupRequest(loginId, "abcd1234", "abcd1234", "홍길동", email, phone);
    }

    private Map<String, Object> findRow(Long id) {
        return jdbcTemplate.queryForMap("SELECT * FROM user_account WHERE id = ?", id);
    }

    private int countRows(String loginId) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM user_account WHERE login_id = ?", Integer.class, loginId);
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
