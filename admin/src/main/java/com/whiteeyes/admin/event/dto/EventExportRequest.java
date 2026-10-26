package com.whiteeyes.admin.event.dto;

import com.whiteeyes.common.event.entity.EventStatus;
import com.whiteeyes.common.event.entity.EventType;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;

/**
 * 이벤트 엑셀 조건(FNC-A-039 입력 항목): 이벤트 목록(FNC-A-036)과 같은 필터에서 page·size만 뺀 것. 비운 값은 그
 * 조건으로 거르지 않는다.
 *
 * @param from 발생 시각 시작(이상)
 * @param to 발생 시각 끝(미만)
 * @param regionId 시·도, 시·군·구, 읍·면·동 중 하나. zoneId와 함께 줄 수 없다
 * @param zoneId 구역 번호. regionId와 함께 줄 수 없다
 * @param types 종류(복수)
 * @param statuses 처리 상태(복수)
 * @param keyword 캠·구역·소속 구·동 이름 검색어
 */
public record EventExportRequest(
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
        Long regionId,
        Long zoneId,
        List<EventType> types,
        List<EventStatus> statuses,
        String keyword) {

    public EventExportRequest {
        types = types == null ? List.of() : List.copyOf(types);
        statuses = statuses == null ? List.of() : List.copyOf(statuses);
        keyword = keyword == null || keyword.isBlank() ? null : keyword.strip();
    }

    /** 조건을 만드는 {@code EventConditionService}가 받는 형태로 바꾼다(page·size는 쓰이지 않는다). */
    public EventSearchRequest toSearchRequest() {
        return new EventSearchRequest(from, to, regionId, zoneId, types, statuses, keyword, null, null, null);
    }
}
