package com.whiteeyes.user.account.dto;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class PasswordConfirmMatchValidator implements ConstraintValidator<PasswordConfirmMatch, SignupRequest> {

    private static final String CONFIRM_FIELD = "passwordConfirm";

    @Override
    public boolean isValid(SignupRequest request, ConstraintValidatorContext context) {
        // 값이 비어 있으면 필드의 @NotNull이 따로 알리므로 여기서는 맞는 것으로 본다
        if (request == null || request.password() == null || request.passwordConfirm() == null) {
            return true;
        }
        if (request.password().equals(request.passwordConfirm())) {
            return true;
        }
        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(context.getDefaultConstraintMessageTemplate())
                .addPropertyNode(CONFIRM_FIELD)
                .addConstraintViolation();
        return false;
    }
}
