package com.whiteeyes.common.account.dto;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 비밀번호 변경 요청의 칸끼리 맞춰 보는 규칙(FNC-C-007): 새 비밀번호와 확인이 같아야 하고,
 * 새 비밀번호는 현재 비밀번호와 달라야 한다. 오류는 해당 칸(newPasswordConfirm, newPassword)에 붙는다.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = PasswordChangeRulesValidator.class)
public @interface PasswordChangeRules {

    String message() default "비밀번호 변경 요청이 올바르지 않습니다.";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
