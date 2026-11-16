package com.whiteeyes.common.account.dto;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

/**
 * 비밀번호 변경 요청의 칸끼리 맞춰 보는 규칙(FNC-C-007): 새 비밀번호와 확인이 같아야 하고,
 * 새 비밀번호는 현재 비밀번호와 달라야 한다. 오류는 해당 칸(newPasswordConfirm, newPassword)에 붙는다.
 */
// 클래스(여기서는 요청 record)에 붙이는 검사 어노테이션이다
@Target(ElementType.TYPE)
// 실행 중에 검증기가 읽어야 하므로 RUNTIME으로 유지한다
@Retention(RetentionPolicy.RUNTIME)
// 실제 검사는 이 검증기가 한다
@Constraint(validatedBy = PasswordChangeRulesValidator.class)
public @interface PasswordChangeRules {

    // Bean Validation 규격이 요구하는 세 속성(message, groups, payload). 검증기가 칸별 메시지를 따로 붙이므로 기본 메시지는 거의 쓰이지 않는다
    String message() default "비밀번호 변경 요청이 올바르지 않습니다.";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
