/*
 * [해석본] 원본: user/src/main/java/com/whiteeyes/user/account/dto/PasswordConfirmMatchValidator.java (feature/30-signup 브랜치)
 * 관련 이슈: #30(FNC-U-002)
 *
 * [무엇] @PasswordConfirmMatch의 실제 검사. 다르면 passwordConfirm 필드에 오류를 붙인다.
 */
package com.whiteeyes.user.account.dto;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class PasswordConfirmMatchValidator implements ConstraintValidator<PasswordConfirmMatch, SignupRequest> {

    // [해석] 오류를 붙일 필드 이름. SignupRequest의 passwordConfirm, 화면의 data-error-for="passwordConfirm"과 같아야 한다.
    private static final String CONFIRM_FIELD = "passwordConfirm";

    @Override
    public boolean isValid(SignupRequest request, ConstraintValidatorContext context) {
        // 값이 비어 있으면 필드의 @NotNull이 따로 알리므로 여기서는 맞는 것으로 본다
        // [해석] 둘 중 하나가 없으면 '필수입니다.' 하나만 보이게 하려는 것. 여기서도 false를 내면 한 칸에 오류가 둘 생긴다.
        if (request == null || request.password() == null || request.passwordConfirm() == null) {
            return true;
        }
        if (request.password().equals(request.passwordConfirm())) {
            return true;
        }
        // [해석] 기본 위반은 레코드 전체(field 이름 없음)에 붙는다. 그것을 끄고, 같은 문구로 passwordConfirm 필드에 붙인 위반을
        //        새로 만든다. 그래서 #96 핸들러가 받는 FieldError의 field가 passwordConfirm이 된다(SignupApiControllerTest로 확인).
        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(context.getDefaultConstraintMessageTemplate())
                .addPropertyNode(CONFIRM_FIELD)
                .addConstraintViolation();
        return false;
    }
}
