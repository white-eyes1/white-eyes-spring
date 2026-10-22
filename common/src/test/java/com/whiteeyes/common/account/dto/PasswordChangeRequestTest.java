package com.whiteeyes.common.account.dto;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 비밀번호 변경 요청의 입력 검사(FNC-C-007 처리 순서 2, 업무 규칙, decisions/0013).
 */
class PasswordChangeRequestTest {

    private static final String CURRENT = "current-pass1!";
    private static final String VALID_NEW = "new-password2@";

    private final Validator validator =
            Validation.buildDefaultValidatorFactory().getValidator();

    private Set<String> invalidFields(PasswordChangeRequest request) {
        return validator.validate(request).stream()
                .map(ConstraintViolation::getPropertyPath)
                .map(Object::toString)
                .collect(Collectors.toSet());
    }

    @Test
    @DisplayName("FNC-C-007 규칙에 맞는 새 비밀번호와 일치하는 확인값이면 통과한다")
    void validRequest_passes() {
        assertThat(invalidFields(new PasswordChangeRequest(CURRENT, VALID_NEW, VALID_NEW)))
                .isEmpty();
    }

    @ParameterizedTest(name = "[{0}]")
    @ValueSource(
            strings = {
                "short1a", // 7자
                "onlyletters", // 숫자 없음
                "123456789", // 영문 없음
                "비밀번호abc1234", // 한글(ASCII가 아님)
                "has space abc1", // 공백
            })
    @DisplayName("FNC-C-007 길이·영문·숫자·ASCII 규칙을 어긴 새 비밀번호는 newPassword 오류다")
    void invalidNewPassword_reportsNewPasswordField(String newPassword) {
        assertThat(invalidFields(new PasswordChangeRequest(CURRENT, newPassword, newPassword)))
                .containsExactly("newPassword");
    }

    @Test
    @DisplayName("FNC-C-007 새 비밀번호가 64자를 넘으면 오류이고 64자는 통과한다")
    void newPassword_lengthBoundary() {
        String max = "a1".repeat(32);
        String over = max + "b";

        assertThat(invalidFields(new PasswordChangeRequest(CURRENT, max, max))).isEmpty();
        assertThat(invalidFields(new PasswordChangeRequest(CURRENT, over, over))).containsExactly("newPassword");
    }

    @Test
    @DisplayName("FNC-C-007 새 비밀번호와 확인이 다르면 newPasswordConfirm 오류다")
    void confirmMismatch_reportsConfirmField() {
        assertThat(invalidFields(new PasswordChangeRequest(CURRENT, VALID_NEW, "different-pass3#")))
                .containsExactly("newPasswordConfirm");
    }

    @Test
    @DisplayName("FNC-C-007 새 비밀번호가 현재 비밀번호와 같으면 newPassword 오류다")
    void sameAsCurrent_reportsNewPasswordField() {
        assertThat(invalidFields(new PasswordChangeRequest(VALID_NEW, VALID_NEW, VALID_NEW)))
                .containsExactly("newPassword");
    }

    @Test
    @DisplayName("FNC-C-007 빠진 값은 칸마다 필수 오류다")
    void missingValues_reportRequired() {
        assertThat(invalidFields(new PasswordChangeRequest(null, null, null)))
                .containsExactlyInAnyOrder("currentPassword", "newPassword", "newPasswordConfirm");
        assertThat(invalidFields(new PasswordChangeRequest("  ", VALID_NEW, VALID_NEW)))
                .containsExactly("currentPassword");
    }

    @Test
    @DisplayName("FNC-C-007 요청을 문자열로 바꿔도 비밀번호 값이 나오지 않는다")
    void toString_hidesPasswords() {
        assertThat(new PasswordChangeRequest(CURRENT, VALID_NEW, VALID_NEW).toString())
                .doesNotContain(CURRENT, VALID_NEW);
    }
}
