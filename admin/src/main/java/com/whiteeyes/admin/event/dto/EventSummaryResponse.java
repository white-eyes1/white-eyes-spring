package com.whiteeyes.admin.event.dto;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

import com.whiteeyes.common.event.entity.EventType;

/**
 * 이벤트 요약(api/public.md GET /api/v1/events/summary, FNC-A-036): 처리 상태별 건수와 종류별 건수.
 *
 * @param byType 종류별 건수(FNC-C-030). 세 종류(CONGESTION·SURGE·CAM_FAULT)를 항상 넣고 없으면 0
 */
public record EventSummaryResponse(
        long total, long unchecked, long checked, long resolved, Map<EventType, Long> byType) {

    /** 조건에 맞는 이벤트가 없을 때의 응답. 종류별 건수도 세 종류 모두 0으로 채운다. */
    public static EventSummaryResponse empty() {
        return new EventSummaryResponse(0, 0, 0, 0, zeroByType());
    }

    /**
     * 처리 상태별 건수와 쿼리로 센 종류별 건수로 응답을 만든다. 합계는 세 상태의 합이다.
     */
    public static EventSummaryResponse of(long unchecked, long checked, long resolved, Map<EventType, Long> byType) {
        // 먼저 0으로 채운 뒤 센 값을 덮어써서, 없는 종류도 0으로 나가게 한다
        Map<EventType, Long> filled = zeroByType();
        filled.putAll(byType);
        // 읽기 전용으로 감싸 응답 이후 바뀌지 않게 한다
        return new EventSummaryResponse(
                unchecked + checked + resolved, unchecked, checked, resolved, Collections.unmodifiableMap(filled));
    }

    // EnumMap이라 JSON 키가 CONGESTION·SURGE·CAM_FAULT 순서로 나간다
    // 모든 종류를 0건으로 채운 새 맵을 만든다
    private static Map<EventType, Long> zeroByType() {
        Map<EventType, Long> byType = new EnumMap<>(EventType.class);
        for (EventType type : EventType.values()) {
            byType.put(type, 0L);
        }
        return byType;
    }
}
