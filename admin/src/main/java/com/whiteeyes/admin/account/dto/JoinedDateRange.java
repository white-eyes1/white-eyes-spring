package com.whiteeyes.admin.account.dto;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

/** 가입일 끝이 시작보다 앞서지 않는지 본다. 오류는 화면의 가입일 끝 칸에 붙도록 joinedTo 필드로 낸다. */
// 필드 하나가 아니라 joinedFrom·joinedTo 두 값을 함께 봐야 해서 클래스(레코드)에 거는 검증 어노테이션이다
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = JoinedDateRangeValidator.class)
public @interface JoinedDateRange {

    // 검증 실패 시 기본 메시지(Bean Validation이 요구하는 3개 속성 중 하나)
    String message() default "가입일 끝은 시작보다 앞설 수 없습니다.";

    // groups·payload는 Bean Validation 규약상 반드시 있어야 하는 속성이며 여기서는 쓰지 않는다
    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
