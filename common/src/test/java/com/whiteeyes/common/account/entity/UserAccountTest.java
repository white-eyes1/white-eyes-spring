package com.whiteeyes.common.account.entity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 사용자 계정 엔티티의 비밀번호 재설정(FNC-A-005). */
class UserAccountTest {

    @Test
    @DisplayName("FNC-A-005 비밀번호를 재설정하면 저장값이 바뀌고 변경 강제가 켜진다")
    void resetPassword_setsPasswordAndForcesChange() {
        UserAccount account = UserAccount.createPending("hong01", "$2a$10$old", "홍길동", null, null);
        assertThat(account.isMustChangePassword()).isFalse();

        account.resetPassword("$2a$10$temporary");

        assertThat(account.getPassword()).isEqualTo("$2a$10$temporary");
        assertThat(account.isMustChangePassword()).isTrue();
        assertThat(account.getStatus()).as("상태는 바뀌지 않는다").isEqualTo(UserAccountStatus.PENDING);
    }

    @Test
    @DisplayName("FNC-C-007 비밀번호를 바꾸면 재설정으로 켜진 변경 강제가 꺼진다")
    void changePassword_clearsForcedChange() {
        UserAccount account = UserAccount.createPending("hong01", "$2a$10$old", "홍길동", null, null);
        account.resetPassword("$2a$10$temporary");

        account.changePassword("$2a$10$new");

        assertThat(account.isMustChangePassword()).isFalse();
    }
}
