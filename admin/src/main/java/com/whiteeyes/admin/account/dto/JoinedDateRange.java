package com.whiteeyes.admin.account.dto;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 가입일 끝이 시작보다 앞서지 않는지 본다. 오류는 화면의 가입일 끝 칸에 붙도록 joinedTo 필드로 낸다. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = JoinedDateRangeValidator.class)
public @interface JoinedDateRange {

    String message() default "가입일 끝은 시작보다 앞설 수 없습니다.";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
