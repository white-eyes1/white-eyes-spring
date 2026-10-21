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
        @NotNull(message = InputRules.REQUIRED_MESSAGE)
                @Pattern(regexp = InputRules.LOGIN_ID_REGEX, message = InputRules.LOGIN_ID_MESSAGE)
                String loginId,
        @NotNull(message = InputRules.REQUIRED_MESSAGE)
                @Pattern(regexp = InputRules.PASSWORD_REGEX, message = InputRules.PASSWORD_MESSAGE)
                String password,
        @NotNull(message = InputRules.REQUIRED_MESSAGE) String passwordConfirm,
        @NotBlank(message = InputRules.REQUIRED_MESSAGE)
                @Size(max = InputRules.NAME_MAX_LENGTH, message = InputRules.NAME_MESSAGE)
                String name,
        @Email(message = InputRules.EMAIL_MESSAGE)
                @Size(max = InputRules.EMAIL_MAX_LENGTH, message = InputRules.EMAIL_MESSAGE)
                String email,
        @Pattern(regexp = InputRules.PHONE_REGEX, message = InputRules.PHONE_MESSAGE) String phone) {}
