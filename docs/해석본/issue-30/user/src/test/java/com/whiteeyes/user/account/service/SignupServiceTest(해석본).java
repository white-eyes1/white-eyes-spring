/*
 * [해석본] 원본: user/src/test/java/com/whiteeyes/user/account/service/SignupServiceTest.java (feature/30-signup 브랜치)
 * 관련 이슈: #29(FNC-U-001), #30(FNC-U-002)
 *
 * [무엇] 서비스를 H2에 실제로 저장하며 확인하는 테스트. #30이 가입 테스트 4개를 더했다.
 * [#29 해석본과의 관계] isLoginIdAvailable 테스트와 givenAccount 설명은 docs/해석본/issue-29/의 같은 파일에 있다.
 * [@Transactional] 테스트마다 롤백한다. 동시성 테스트만 롤백할 수 없어 다른 클래스로 뺐다.
 */
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
    // [해석] 진짜 리포지토리를 감싼 스파이. 평소에는 그대로 동작하고, 한 테스트에서만 existsByLoginId를 false로 바꾼다.
    //        빈을 바꾸는 설정이라 이 클래스는 다른 테스트 클래스와 별도 컨텍스트로 뜬다(조금 느림).
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
    // [해석] 저장된 행은 JdbcTemplate으로 읽는다. JPA로 읽으면 같은 트랜잭션의 1차 캐시에 있는 엔티티가 돌아와서
    //        DB에 실제로 들어간 값을 보지 못한다.
    void signup_savesPendingWithBcrypt() {
        SignupResponse response = signupService.signup(request(LOGIN_ID, "a@b.com", "010-1234-5678"));

        assertThat(response.status()).isEqualTo(UserAccountStatus.PENDING);
        Map<String, Object> row = findRow(response.id());
        assertThat(row.get("login_id")).isEqualTo(LOGIN_ID);
        assertThat(row.get("status")).isEqualTo("PENDING");
        // [해석] H2 create-drop에서는 boolean 칼럼이라 Boolean으로 읽힌다. MySQL TINYINT(1)에서는 읽히는 형이 다를 수 있다.
        assertThat(row.get("must_change_password")).isEqualTo(false);
        assertThat(row.get("name")).isEqualTo("홍길동");
        assertThat(row.get("email")).isEqualTo("a@b.com");
        assertThat(row.get("phone")).isEqualTo("010-1234-5678");
        String password = (String) row.get("password");
        // [해석] 60자: bcrypt 결과 길이(tables.md password 비고). 평문이 아님을 보고, matches로 원래 비밀번호와 맞는지 본다.
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
    // [해석] 탈퇴해도 행이 남아 같은 아이디로 재가입할 수 없다(auth-005, FNC-U-004 검수 기준 '재가입 시 아이디 중복').
    @DisplayName("FNC-U-002 탈퇴 계정의 아이디로는 가입할 수 없다")
    void signup_withdrawnLoginId_duplicate() {
        givenAccount(LOGIN_ID, UserAccountStatus.WITHDRAWN);

        assertThatThrownBy(() -> signupService.signup(request(LOGIN_ID, null, null)))
                .isInstanceOf(DuplicateLoginIdException.class);
        assertThat(countRows(LOGIN_ID)).isEqualTo(1);
    }

    @Test
    // [해석] 동시 가입에서 생기는 '미리 확인은 통과했는데 INSERT가 막힘'을 매번 확실히 재현한다.
    //        hasCauseInstanceOf로 미리 확인이 아니라 DB(DataIntegrityViolationException)가 막았음을 확인한다.
    //        실패한 INSERT 뒤 트랜잭션이 롤백 전용이 되지만, 테스트는 끝나면 어차피 롤백한다.
    @DisplayName("FNC-U-002 미리 확인을 통과해도 DB 유일 조건이 막아 아이디 중복으로 응답")
    void signup_uniqueConstraint_duplicate() {
        givenAccount(LOGIN_ID, UserAccountStatus.ACTIVE);
        // 동시 가입에서 두 요청이 모두 미리 확인을 통과한 상황을 만든다
        // [해석] 스파이에는 given(spy.method()).willReturn(...) 대신 이 형식을 쓴다. 앞 형식은 설정하면서 진짜 메서드를 한 번 부른다.
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
