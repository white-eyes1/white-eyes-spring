package com.whiteeyes.user.account.dto;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import com.whiteeyes.common.constant.InputRules;

/**
 * 비밀번호와 비밀번호 확인이 같은지 본다. 오류는 화면의 비밀번호 확인 칸에 붙도록 passwordConfirm 필드로 낸다.
 */
// 클래스(레코드) 전체에 거는 검증이다. 비밀번호와 확인 칸 두 값을 함께 비교해야 해서 필드 하나에 거는 방식으로는 만들 수 없다.
// 실제 비교는 PasswordConfirmMatchValidator가 한다.
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = PasswordConfirmMatchValidator.class)
public @interface PasswordConfirmMatch {

    // 아래 세 속성(message, groups, payload)은 Bean Validation 규격이 요구하는 필수 모양이다.
    String message() default InputRules.PASSWORD_CONFIRM_MESSAGE;

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
