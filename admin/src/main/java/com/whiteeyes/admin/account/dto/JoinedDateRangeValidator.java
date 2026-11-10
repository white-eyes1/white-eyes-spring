package com.whiteeyes.admin.account.dto;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class JoinedDateRangeValidator implements ConstraintValidator<JoinedDateRange, UserSearchRequest> {

    private static final String TO_FIELD = "joinedTo";

    @Override
    public boolean isValid(UserSearchRequest request, ConstraintValidatorContext context) {
        // 한쪽만 주면 그쪽으로만 거르므로 비교할 것이 없다
        if (request == null || request.joinedFrom() == null || request.joinedTo() == null) {
            return true;
        }
        if (!request.joinedTo().isBefore(request.joinedFrom())) {
            return true;
        }
        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(context.getDefaultConstraintMessageTemplate())
                .addPropertyNode(TO_FIELD)
                .addConstraintViolation();
        return false;
    }
}
