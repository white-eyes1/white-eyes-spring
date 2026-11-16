package com.whiteeyes.user.account.dto;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * @PasswordConfirmMatch의 실제 검사기. 가입 요청의 password와 passwordConfirm이 같은지 확인한다.
 */
public class PasswordConfirmMatchValidator implements ConstraintValidator<PasswordConfirmMatch, SignupRequest> {

    // 오류를 붙일 필드 이름. 클래스 수준 검증은 기본적으로 오류가 객체 전체에 붙으므로, 화면의 확인 칸 아래에 문구가 뜨도록 필드를 직접 지정한다.
    private static final String CONFIRM_FIELD = "passwordConfirm";

    /**
     * 두 비밀번호가 같으면 true. 다르면 기본 오류를 끄고 passwordConfirm 필드에 오류를 붙여 false를 돌려준다.
     */
    @Override
    public boolean isValid(SignupRequest request, ConstraintValidatorContext context) {
        // 값이 비어 있으면 필드의 @NotNull이 따로 알리므로 여기서는 맞는 것으로 본다
        if (request == null || request.password() == null || request.passwordConfirm() == null) {
            return true;
        }
        // 같으면 통과
        if (request.password().equals(request.passwordConfirm())) {
            return true;
        }
        // 기본 오류(객체 수준)를 끄고 아래에서 필드 수준 오류를 새로 만든다.
        context.disableDefaultConstraintViolation();
        // 문구는 애너테이션에 적은 기본 문구(InputRules.PASSWORD_CONFIRM_MESSAGE)를 그대로 쓴다.
        context.buildConstraintViolationWithTemplate(context.getDefaultConstraintMessageTemplate())
                .addPropertyNode(CONFIRM_FIELD)
                .addConstraintViolation();
        return false;
    }
}
