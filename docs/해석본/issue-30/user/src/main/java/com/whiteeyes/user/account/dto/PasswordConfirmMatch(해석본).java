/*
 * [해석본] 원본: user/src/main/java/com/whiteeyes/user/account/dto/PasswordConfirmMatch.java (feature/30-signup 브랜치)
 * 관련 이슈: #30(FNC-U-002 처리 순서 1 '비밀번호 확인 일치')
 *
 * [무엇] 비밀번호와 비밀번호 확인이 같은지 보는 제약 애너테이션. SignupRequest 레코드에 붙인다.
 * [왜 @AssertTrue가 아닌가] @AssertTrue boolean isPasswordConfirmed()로도 검사할 수 있지만, 그러면 오류의 field 이름이
 *   메서드에서 나온 passwordConfirmed가 되어 화면 칸(passwordConfirm)과 맞지 않는다. 클래스에 붙이는 제약은
 *   오류를 붙일 필드를 직접 정할 수 있다(PasswordConfirmMatchValidator).
 */
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
// [해석] 두 필드를 비교해야 해서 필드가 아니라 클래스(레코드)에 붙인다.
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = PasswordConfirmMatchValidator.class)
public @interface PasswordConfirmMatch {

    // [해석] message·groups·payload 세 속성은 Bean Validation 규약상 모든 제약 애너테이션에 있어야 한다. 빼면 검사할 때 오류가 난다.
    String message() default InputRules.PASSWORD_CONFIRM_MESSAGE;

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
