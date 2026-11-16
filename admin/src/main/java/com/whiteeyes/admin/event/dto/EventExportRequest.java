package com.whiteeyes.admin.event.dto;

import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.format.annotation.DateTimeFormat;

import com.whiteeyes.common.event.entity.EventStatus;
import com.whiteeyes.common.event.entity.EventType;

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
 * @param overlap true면 기간을 발생 시각이 아니라 [from, to)와 겹치는 이벤트로 본다(목록과 같다). 비우면 false
 */
public record EventExportRequest(
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
        Long regionId,
        Long zoneId,
        List<EventType> types,
        List<EventStatus> statuses,
        String keyword,
        Boolean overlap) {

    // 컴팩트 생성자: 요청 값을 정규화한다. 비운 목록은 빈 목록(조건 없음), 공백뿐인 검색어는 null, overlap은 null이면 false
    public EventExportRequest {
        // 불변 복사본으로 만들어 이후 바뀌지 않게 한다
        types = types == null ? List.of() : List.copyOf(types);
        statuses = statuses == null ? List.of() : List.copyOf(statuses);
        // 앞뒤 공백을 떼어 같은 검색어가 같게 취급되게 한다
        keyword = keyword == null || keyword.isBlank() ? null : keyword.strip();
        overlap = overlap != null && overlap;
    }

    /** 조건을 만드는 {@code EventConditionService}가 받는 형태로 바꾼다(page·size는 쓰이지 않는다). */
    public EventSearchRequest toSearchRequest() {
        return new EventSearchRequest(from, to, regionId, zoneId, types, statuses, keyword, overlap, null, null);
    }
}
