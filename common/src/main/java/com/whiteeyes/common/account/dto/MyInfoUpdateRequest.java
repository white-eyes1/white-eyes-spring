package com.whiteeyes.common.account.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import com.whiteeyes.common.constant.InputRules;

/**
 * 내 정보 수정 요청(PATCH /api/v1/me, FNC-C-006). 보낸 필드만 고치므로 모든 칸이 선택이고, 보냈을 때만 규칙을 검사한다.
 * 메일·연락처는 빈 문자열을 보내면 비운다. 아이디·역할·상태는 받지 않는다(요청에 있어도 무시).
 */
// 칸마다 null이면 "수정 안 함"이라 검사 어노테이션은 값이 있을 때만 걸린다(@NotNull 없음)
public record MyInfoUpdateRequest(
        // 이름: 최대 길이 이내이고, 공백만으로 이루어지지 않아야 한다(아래 정규식은 공백이 아닌 글자가 하나 이상 있어야 통과)
        @Size(max = InputRules.NAME_MAX_LENGTH, message = InputRules.NAME_MESSAGE)
                @Pattern(regexp = ".*\\S.*", message = InputRules.NAME_MESSAGE)
                String name,
        // 메일: 형식과 최대 길이를 검사한다. 빈 문자열은 @Email을 통과해 "비우기"로 쓰인다
        @Email(message = InputRules.EMAIL_MESSAGE)
                @Size(max = InputRules.EMAIL_MAX_LENGTH, message = InputRules.EMAIL_MESSAGE)
                String email,
        // 연락처: 공통 규칙 정규식(숫자·하이픈 0~20자)을 따른다. 0자도 통과하므로 빈 문자열은 비우기로 쓰인다
        @Pattern(regexp = InputRules.PHONE_REGEX, message = InputRules.PHONE_MESSAGE) String phone) {}
