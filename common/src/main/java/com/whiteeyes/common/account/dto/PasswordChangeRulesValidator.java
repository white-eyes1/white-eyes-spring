package com.whiteeyes.common.account.dto;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import com.whiteeyes.common.constant.InputRules;

/** {@link PasswordChangeRules}를 검사한다. */
public class PasswordChangeRulesValidator implements ConstraintValidator<PasswordChangeRules, PasswordChangeRequest> {

    // 오류를 붙일 칸 이름. 화면이 해당 입력칸 옆에 메시지를 보이게 한다
    private static final String NEW_PASSWORD_FIELD = "newPassword";
    private static final String CONFIRM_FIELD = "newPasswordConfirm";
    private static final String SAME_AS_CURRENT_MESSAGE = "새 비밀번호는 현재 비밀번호와 달라야 합니다.";

    @Override
    /**
     * 요청 전체를 검사한다. 위반마다 칸을 지정해 오류를 붙이고, 하나라도 있으면 false를 돌려준다.
     */
    public boolean isValid(PasswordChangeRequest request, ConstraintValidatorContext context) {
        // 요청 자체가 없는 경우는 다른 검사가 다루므로 통과시킨다
        if (request == null) {
            return true;
        }
        // 클래스 전체에 붙는 기본 오류는 쓰지 않고, 아래에서 칸별 오류만 직접 만든다
        context.disableDefaultConstraintViolation();
        // 위반이 하나라도 생기면 false로 바꾼다(여러 위반을 한 번에 알리려고 중간에 끝내지 않는다)
        boolean valid = true;
        // 값이 비어 있으면 필드의 @NotNull·@NotBlank가 따로 알리므로 여기서는 비교하지 않는다
        if (request.newPassword() != null && request.newPasswordConfirm() != null) {
            // 새 비밀번호와 확인이 다르면 확인 칸에 오류를 붙인다
            if (!request.newPassword().equals(request.newPasswordConfirm())) {
                addViolation(context, CONFIRM_FIELD, InputRules.PASSWORD_CONFIRM_MESSAGE);
                valid = false;
            }
        }
        // 새 비밀번호가 현재 것과 같으면 바꾸는 의미가 없으므로 새 비밀번호 칸에 오류를 붙인다
        if (request.newPassword() != null && request.newPassword().equals(request.currentPassword())) {
            addViolation(context, NEW_PASSWORD_FIELD, SAME_AS_CURRENT_MESSAGE);
            valid = false;
        }
        return valid;
    }

    /** 지정한 칸(field)에 오류 메시지를 붙인다. 템플릿 문자열로 만들므로 메시지는 고정 문구여야 한다. */
    private void addViolation(ConstraintValidatorContext context, String field, String message) {
        context.buildConstraintViolationWithTemplate(message)
                .addPropertyNode(field)
                .addConstraintViolation();
    }
}
