package com.whiteeyes.common.account.dto;

import com.whiteeyes.common.constant.InputRules;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/** {@link PasswordChangeRules}를 검사한다. */
public class PasswordChangeRulesValidator implements ConstraintValidator<PasswordChangeRules, PasswordChangeRequest> {

    private static final String NEW_PASSWORD_FIELD = "newPassword";
    private static final String CONFIRM_FIELD = "newPasswordConfirm";
    private static final String SAME_AS_CURRENT_MESSAGE = "새 비밀번호는 현재 비밀번호와 달라야 합니다.";

    @Override
    public boolean isValid(PasswordChangeRequest request, ConstraintValidatorContext context) {
        if (request == null) {
            return true;
        }
        context.disableDefaultConstraintViolation();
        boolean valid = true;
        // 값이 비어 있으면 필드의 @NotNull·@NotBlank가 따로 알리므로 여기서는 비교하지 않는다
        if (request.newPassword() != null && request.newPasswordConfirm() != null) {
            if (!request.newPassword().equals(request.newPasswordConfirm())) {
                addViolation(context, CONFIRM_FIELD, InputRules.PASSWORD_CONFIRM_MESSAGE);
                valid = false;
            }
        }
        if (request.newPassword() != null && request.newPassword().equals(request.currentPassword())) {
            addViolation(context, NEW_PASSWORD_FIELD, SAME_AS_CURRENT_MESSAGE);
            valid = false;
        }
        return valid;
    }

    private void addViolation(ConstraintValidatorContext context, String field, String message) {
        context.buildConstraintViolationWithTemplate(message)
                .addPropertyNode(field)
                .addConstraintViolation();
    }
}
