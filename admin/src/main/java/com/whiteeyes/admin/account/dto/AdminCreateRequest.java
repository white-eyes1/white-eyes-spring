package com.whiteeyes.admin.account.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import com.whiteeyes.common.constant.InputRules;

/** 관리자 계정 생성 요청(POST /api/v1/admins, FNC-A-009). email·phone은 선택이라 비어 있어도 된다. 규칙은 회원가입과 같다. */
// 각 필드 아래의 검증 어노테이션이 요청 형식을 검사하고, 실패하면 400(fieldErrors)으로 응답한다.
// 규칙 값과 문구는 회원가입과 같도록 InputRules 상수를 함께 쓴다
public record AdminCreateRequest(
        // 아이디: 필수이고 형식(LOGIN_ID_REGEX)을 지켜야 한다. 중복은 서비스가 따로 확인한다
        @NotNull(message = InputRules.REQUIRED_MESSAGE)
                @Pattern(regexp = InputRules.LOGIN_ID_REGEX, message = InputRules.LOGIN_ID_MESSAGE)
                String loginId,
        // 이름: 공백만 있는 값도 거부하고 최대 길이를 제한한다
        @NotBlank(message = InputRules.REQUIRED_MESSAGE)
                @Size(max = InputRules.NAME_MAX_LENGTH, message = InputRules.NAME_MESSAGE)
                String name,
        // 메일: 선택 항목이라 비어 있어도 되고, 값이 있으면 메일 형식과 길이를 검사한다
        @Email(message = InputRules.EMAIL_MESSAGE)
                @Size(max = InputRules.EMAIL_MAX_LENGTH, message = InputRules.EMAIL_MESSAGE)
                String email,
        // 연락처: 선택 항목이며 값이 있으면 형식을 검사한다
        @Pattern(regexp = InputRules.PHONE_REGEX, message = InputRules.PHONE_MESSAGE) String phone) {}
