package com.whiteeyes.common.account.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import com.whiteeyes.common.constant.InputRules;

/**
 * 비밀번호 변경 요청(PATCH /api/v1/me/password, FNC-C-007).
 * 현재 비밀번호는 저장된 해시와 맞는지만 보므로 형식 규칙을 걸지 않는다. 새 비밀번호 규칙은 회원가입과 같다(decisions/0013).
 */
// 칸끼리 맞춰 보는 검사(새 비밀번호=확인, 현재와 다름)를 클래스 수준으로 건다
@PasswordChangeRules
public record PasswordChangeRequest(
        // 현재 비밀번호: 비어 있지 않기만 하면 된다. 맞는지는 서비스가 저장된 해시와 비교한다
        @NotBlank(message = InputRules.REQUIRED_MESSAGE) String currentPassword,
        // 새 비밀번호: 반드시 있어야 하고 회원가입과 같은 규칙(정규식)을 따른다
        @NotNull(message = InputRules.REQUIRED_MESSAGE)
                @Pattern(regexp = InputRules.PASSWORD_REGEX, message = InputRules.PASSWORD_MESSAGE)
                String newPassword,
        // 새 비밀번호 확인: 새 비밀번호와 같은지는 PasswordChangeRulesValidator가 본다
        @NotNull(message = InputRules.REQUIRED_MESSAGE) String newPasswordConfirm) {

    /** 오류 로그나 디버깅에서 비밀번호가 문자열로 새지 않게 값을 빼고 만든다. */
    @Override
    public String toString() {
        return "PasswordChangeRequest[passwords hidden]";
    }
}
