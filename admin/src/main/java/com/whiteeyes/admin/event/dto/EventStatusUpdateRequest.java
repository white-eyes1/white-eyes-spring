package com.whiteeyes.admin.event.dto;

import com.whiteeyes.common.constant.InputRules;
import com.whiteeyes.common.event.entity.EventActionCode;
import com.whiteeyes.common.event.entity.EventStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 이벤트 처리 상태 변경 요청(PATCH /api/v1/events/{id}/status, FNC-A-038).
 *
 * @param expectedStatus 화면이 보고 있던 현재 상태. 서버의 현재 상태와 다르면 다른 관리자가 먼저 처리한 것이다
 * @param status 바꿀 상태
 * @param actionCode 조치 항목. 조치완료일 때만 보낸다
 * @param actionNote 조치 메모. 200자 이하, 조치 항목이 OTHER면 필수
 */
public record EventStatusUpdateRequest(
        @NotNull(message = InputRules.REQUIRED_MESSAGE) EventStatus expectedStatus,
        @NotNull(message = InputRules.REQUIRED_MESSAGE) EventStatus status,
        EventActionCode actionCode,
        @Size(max = ACTION_NOTE_MAX_LENGTH, message = ACTION_NOTE_MESSAGE) String actionNote) {

    /** conventions.md 입력값 규칙의 조치 메모 길이. */
    public static final int ACTION_NOTE_MAX_LENGTH = 200;

    public static final String ACTION_NOTE_MESSAGE = "조치 메모는 200자 이하여야 합니다.";
}
