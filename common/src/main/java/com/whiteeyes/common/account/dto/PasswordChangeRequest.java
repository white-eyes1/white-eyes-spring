package com.whiteeyes.common.account.dto;

import com.whiteeyes.common.constant.InputRules;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * 비밀번호 변경 요청(PATCH /api/v1/me/password, FNC-C-007).
 * 현재 비밀번호는 저장된 해시와 맞는지만 보므로 형식 규칙을 걸지 않는다. 새 비밀번호 규칙은 회원가입과 같다(decisions/0013).
 */
@PasswordChangeRules
public record PasswordChangeRequest(
        @NotBlank(message = InputRules.REQUIRED_MESSAGE) String currentPassword,
        @NotNull(message = InputRules.REQUIRED_MESSAGE)
                @Pattern(regexp = InputRules.PASSWORD_REGEX, message = InputRules.PASSWORD_MESSAGE)
                String newPassword,
        @NotNull(message = InputRules.REQUIRED_MESSAGE) String newPasswordConfirm) {

    /** 오류 로그나 디버깅에서 비밀번호가 문자열로 새지 않게 값을 빼고 만든다. */
    @Override
    public String toString() {
        return "PasswordChangeRequest[passwords hidden]";
    }
}
