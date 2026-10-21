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
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = PasswordConfirmMatchValidator.class)
public @interface PasswordConfirmMatch {

    String message() default InputRules.PASSWORD_CONFIRM_MESSAGE;

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
