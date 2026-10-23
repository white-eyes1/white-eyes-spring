package com.whiteeyes.admin.event.dto;

import com.whiteeyes.common.event.entity.EventStatus;
import com.whiteeyes.common.event.entity.EventType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;

/**
 * 이벤트 목록·요약 조회 조건(FNC-A-036 입력 항목). 비운 값은 그 조건으로 거르지 않는다.
 *
 * @param from 발생 시각 시작(이상)
 * @param to 발생 시각 끝(미만)
 * @param regionId 시·도, 시·군·구, 읍·면·동 중 하나. zoneId와 함께 줄 수 없다
 * @param zoneId 구역 번호. regionId와 함께 줄 수 없다
 * @param types 종류(복수)
 * @param statuses 처리 상태(복수). 요약에서는 쓰지 않는다
 * @param keyword 캠·구역·소속 구·동 이름 검색어
 * @param page 0부터
 * @param size 1~100
 */
public record EventSearchRequest(
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
        Long regionId,
        Long zoneId,
        List<EventType> types,
        List<EventStatus> statuses,
        String keyword,
        @Min(value = 0, message = PAGE_MESSAGE) Integer page,
        @Min(value = 1, message = SIZE_MESSAGE) @Max(value = MAX_SIZE, message = SIZE_MESSAGE) Integer size) {

    // 페이지 기본값·최대값(conventions.md '공통 API 규칙 > 페이지')
    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 100;

    private static final String PAGE_MESSAGE = "페이지는 0 이상이어야 합니다.";
    private static final String SIZE_MESSAGE = "한 페이지 개수는 1~100이어야 합니다.";

    public EventSearchRequest {
        types = types == null ? List.of() : List.copyOf(types);
        statuses = statuses == null ? List.of() : List.copyOf(statuses);
        keyword = keyword == null || keyword.isBlank() ? null : keyword.strip();
        page = page == null ? 0 : page;
        size = size == null ? DEFAULT_SIZE : size;
    }
}
