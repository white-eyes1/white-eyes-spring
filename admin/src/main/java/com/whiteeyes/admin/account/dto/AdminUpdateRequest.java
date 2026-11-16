package com.whiteeyes.admin.account.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import com.whiteeyes.common.constant.InputRules;

/**
 * 관리자 정보 수정 요청(PATCH /api/v1/admins/{id}, FNC-A-010). 사용자 정보 수정(FNC-A-004)과 같은 규칙이다.
 * 보낸 필드만 고치므로 모든 칸이 선택이고, 보냈을 때만 규칙을 검사한다. 메일·연락처는 빈 문자열을 보내면 비운다.
 * 아이디·역할·상태는 받지 않는다(요청에 있어도 무시).
 */
// 모든 필드가 선택이다. null이면 '보내지 않음'이라 검사하지 않고 서비스도 저장된 값을 그대로 둔다
public record AdminUpdateRequest(
        @Size(max = InputRules.NAME_MAX_LENGTH, message = InputRules.NAME_MESSAGE)
                // 공백이 아닌 글자가 하나라도 있어야 한다(공백만 있는 이름 거부). 이름 필드는 길이 제한과 함께 검사한다
                @Pattern(regexp = ".*\\S.*", message = InputRules.NAME_MESSAGE)
                String name,
        // 빈 문자열은 '비우기' 요청이라 형식 검사를 통과하고, 서비스가 NULL로 저장한다
        @Email(message = InputRules.EMAIL_MESSAGE)
                @Size(max = InputRules.EMAIL_MAX_LENGTH, message = InputRules.EMAIL_MESSAGE)
                String email,
        @Pattern(regexp = InputRules.PHONE_REGEX, message = InputRules.PHONE_MESSAGE) String phone) {}
