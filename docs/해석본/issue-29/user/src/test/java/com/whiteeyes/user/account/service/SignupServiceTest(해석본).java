/*
 * [해석본] 원본: user/src/test/java/com/whiteeyes/user/account/service/SignupServiceTest.java (29-fnc-u-001-아이디-중복-확인 브랜치)
 * 관련 이슈: #29(FNC-U-001)
 *
 * [무엇] 이번 이슈의 완료 기준 '탈퇴 계정 아이디가 사용 불가로 나오는지'를 확인하는 테스트.
 * [왜 가짜(Mock) 리포지토리를 쓰지 않았나] 핵심은 'existsByLoginId 쿼리가 상태와 관계없이 행을 찾는가'다.
 *   리포지토리를 가짜로 바꾸면 그 쿼리를 확인할 수 없다. 그래서 @SpringBootTest로 앱을 띄우고 H2에 실제 행을 넣어 확인한다.
 *   테이블은 테스트 설정의 create-drop으로 UserAccount 엔티티대로 만들어진다.
 * [@Transactional] 테스트마다 끝나면 넣은 행을 되돌린다(rollback). 그래서 모든 테스트가 같은 아이디 hong01을 써도 서로 부딪치지 않는다.
 */
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

    // [해석] 테스트에서도 생성자 주입. common의 PasswordEncoderConfigTest와 같은 방식(@Autowired를 매개변수에).
    SignupServiceTest(@Autowired SignupService signupService, @Autowired JdbcTemplate jdbcTemplate) {
        this.signupService = signupService;
        this.jdbcTemplate = jdbcTemplate;
    }

    // [해석] 완료 기준 그대로. 이 테스트가 깨지면 탈퇴한 사람의 아이디로 다시 가입할 수 있게 된 것이다(auth-005 위반).
    @Test
    @DisplayName("FNC-U-001 탈퇴 계정의 아이디는 사용할 수 없다")
    void isLoginIdAvailable_withdrawnAccount_false() {
        givenAccount(LOGIN_ID, UserAccountStatus.WITHDRAWN);

        assertThat(signupService.isLoginIdAvailable(LOGIN_ID)).isFalse();
    }

    // [해석] 탈퇴만 특별히 걸러 내는 게 아니라 '있으면 무조건 사용 불가'인지 보려고 나머지 두 상태도 확인한다.
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

    // [해석] 다른 아이디(other01)가 있어도 hong01은 쓸 수 있어야 한다. 빈 테이블이면 쿼리가 틀려도 통과할 수 있어서 행 하나를 넣어 둔다.
    @Test
    @DisplayName("FNC-U-001 없는 아이디는 사용할 수 있다")
    void isLoginIdAvailable_noAccount_true() {
        givenAccount("other01", UserAccountStatus.ACTIVE);

        assertThat(signupService.isLoginIdAvailable(LOGIN_ID)).isTrue();
    }

    // [해석] JPA(엔티티 저장)가 아니라 SQL로 넣는 이유: UserAccount에는 아직 생성자·상태 변경 메서드가 없다(#30·#64에서 추가).
    //        테스트 때문에 엔티티에 생성자를 미리 열지 않으려고 이렇게 했다.
    // [해석] created_at·updated_at을 직접 넣는 이유: create-drop으로 만든 H2 테이블에는 DB 기본값(CURRENT_TIMESTAMP)이 없어서
    //        비워 두면 NOT NULL 위반이 될 수 있다.
    // [해석] password 값은 60자 bcrypt 모양의 아무 문자열이다. 이 테스트는 비밀번호를 보지 않는다.
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
