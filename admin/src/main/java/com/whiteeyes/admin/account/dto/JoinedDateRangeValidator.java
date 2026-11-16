package com.whiteeyes.admin.account.dto;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/** {@link JoinedDateRange} 검증 구현. 가입일 끝(joinedTo)이 시작(joinedFrom)보다 앞서면 오류를 joinedTo 필드에 붙인다. */
public class JoinedDateRangeValidator implements ConstraintValidator<JoinedDateRange, UserSearchRequest> {

    // 오류를 붙일 필드 이름. 화면의 '가입일 끝' 입력 칸 아래에 문구가 보이게 한다
    private static final String TO_FIELD = "joinedTo";

    /** 범위가 올바르면 true. 같은 날(시작=끝)은 허용한다. */
    @Override
    public boolean isValid(UserSearchRequest request, ConstraintValidatorContext context) {
        // 한쪽만 주면 그쪽으로만 거르므로 비교할 것이 없다
        if (request == null || request.joinedFrom() == null || request.joinedTo() == null) {
            return true;
        }
        // 끝이 시작보다 앞서지 않으면(같은 날 포함) 정상
        if (!request.joinedTo().isBefore(request.joinedFrom())) {
            return true;
        }
        // 기본 오류(객체 전체에 붙는 오류)는 끄고, 아래에서 joinedTo 필드에 붙인 오류를 대신 등록한다
        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(context.getDefaultConstraintMessageTemplate())
                .addPropertyNode(TO_FIELD)
                .addConstraintViolation();
        return false;
    }
}
