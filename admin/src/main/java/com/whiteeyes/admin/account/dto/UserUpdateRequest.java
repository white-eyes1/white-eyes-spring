package com.whiteeyes.admin.account.dto;

import com.whiteeyes.common.constant.InputRules;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 사용자 정보 수정 요청(PATCH /api/v1/users/{id}, FNC-A-004). 내 정보 수정(FNC-C-006)과 같은 규칙이다.
 * 보낸 필드만 고치므로 모든 칸이 선택이고, 보냈을 때만 규칙을 검사한다. 메일·연락처는 빈 문자열을 보내면 비운다.
 * 아이디·상태는 받지 않는다(요청에 있어도 무시, 업무 규칙 '아이디·역할은 바꿀 수 없음').
 */
public record UserUpdateRequest(
        @Size(max = InputRules.NAME_MAX_LENGTH, message = InputRules.NAME_MESSAGE)
                @Pattern(regexp = ".*\\S.*", message = InputRules.NAME_MESSAGE)
                String name,
        @Email(message = InputRules.EMAIL_MESSAGE)
                @Size(max = InputRules.EMAIL_MAX_LENGTH, message = InputRules.EMAIL_MESSAGE)
                String email,
        @Pattern(regexp = InputRules.PHONE_REGEX, message = InputRules.PHONE_MESSAGE) String phone) {}
