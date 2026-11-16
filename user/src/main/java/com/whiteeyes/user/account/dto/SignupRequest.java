package com.whiteeyes.user.account.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import com.whiteeyes.common.constant.InputRules;

/** 회원가입 요청(POST /api/v1/signup). email·phone은 선택이라 비어 있어도 된다. */
@PasswordConfirmMatch
public record SignupRequest(
        // 아이디: 필수 + 형식 검사(규칙은 common의 InputRules에 모아 두어 화면 검증과 같게 한다).
        @NotNull(message = InputRules.REQUIRED_MESSAGE)
                @Pattern(regexp = InputRules.LOGIN_ID_REGEX, message = InputRules.LOGIN_ID_MESSAGE)
                String loginId,
        // 비밀번호: 필수 + 형식 검사.
        @NotNull(message = InputRules.REQUIRED_MESSAGE)
                @Pattern(regexp = InputRules.PASSWORD_REGEX, message = InputRules.PASSWORD_MESSAGE)
                String password,
        // 비밀번호 확인: 필수. 같은지는 클래스에 붙은 @PasswordConfirmMatch가 비교한다.
        @NotNull(message = InputRules.REQUIRED_MESSAGE) String passwordConfirm,
        // 이름: 공백만 있는 값도 막고 길이 상한을 둔다.
        @NotBlank(message = InputRules.REQUIRED_MESSAGE)
                @Size(max = InputRules.NAME_MAX_LENGTH, message = InputRules.NAME_MESSAGE)
                String name,
        // 이메일(선택): 값이 있을 때만 형식·길이를 검사한다(null이나 빈 값은 @Email이 통과시킨다).
        @Email(message = InputRules.EMAIL_MESSAGE)
                @Size(max = InputRules.EMAIL_MAX_LENGTH, message = InputRules.EMAIL_MESSAGE)
                String email,
        // 전화번호(선택): 값이 있을 때만 형식을 검사한다.
        @Pattern(regexp = InputRules.PHONE_REGEX, message = InputRules.PHONE_MESSAGE) String phone) {}
