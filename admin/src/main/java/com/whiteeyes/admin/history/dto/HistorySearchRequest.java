package com.whiteeyes.admin.history.dto;

import com.whiteeyes.common.history.entity.ActorType;
import com.whiteeyes.common.history.entity.WorkAction;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import org.springframework.format.annotation.DateTimeFormat;

/**
 * 작업 기록 조회 조건(FNC-A-053 입력 항목, GET /api/v1/histories). 비운 값은 그 조건으로 거르지 않는다.
 *
 * @param from 기록 시각 시작(이상)
 * @param to 기록 시각 끝(미만)
 * @param actorType 행동한 계정 종류. 사용자·관리자가 같은 아이디를 가질 수 있어 아이디와 함께 거른다
 * @param loginId 행동한 계정의 아이디(기록 당시 값, 정확히 일치). 계정 종류를 비우면 두 종류 모두에서 찾는다
 * @param action 행동 종류
 * @param page 0부터
 * @param size 1~100
 */
public record HistorySearchRequest(
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
        ActorType actorType,
        @Size(max = LOGIN_ID_MAX_LENGTH, message = LOGIN_ID_MESSAGE) String loginId,
        WorkAction action,
        @Min(value = 0, message = PAGE_MESSAGE) Integer page,
        @Min(value = 1, message = SIZE_MESSAGE) @Max(value = MAX_SIZE, message = SIZE_MESSAGE) Integer size) {

    // 페이지 기본값·최대값(conventions.md '공통 API 규칙 > 페이지')
    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 100;

    // work_history.actor_login_id 길이(tables.md)
    private static final int LOGIN_ID_MAX_LENGTH = 20;
    private static final String LOGIN_ID_MESSAGE = "아이디는 20자 이하여야 합니다.";
    private static final String PAGE_MESSAGE = "페이지는 0 이상이어야 합니다.";
    private static final String SIZE_MESSAGE = "한 페이지 개수는 1~100이어야 합니다.";

    public HistorySearchRequest {
        loginId = loginId == null || loginId.isBlank() ? null : loginId.strip();
        page = page == null ? 0 : page;
        size = size == null ? DEFAULT_SIZE : size;
    }
}
