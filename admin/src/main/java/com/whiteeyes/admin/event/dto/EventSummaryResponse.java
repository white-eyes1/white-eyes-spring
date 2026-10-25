package com.whiteeyes.admin.event.dto;

import com.whiteeyes.common.event.entity.EventType;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * 이벤트 요약(api/public.md GET /api/v1/events/summary, FNC-A-036): 처리 상태별 건수와 종류별 건수.
 *
 * @param byType 종류별 건수(FNC-C-030). 세 종류(CONGESTION·SURGE·CAM_FAULT)를 항상 넣고 없으면 0
 */
public record EventSummaryResponse(long total, long unchecked, long checked, long resolved, Map<EventType, Long> byType) {

    public static EventSummaryResponse empty() {
        return new EventSummaryResponse(0, 0, 0, 0, zeroByType());
    }

    public static EventSummaryResponse of(long unchecked, long checked, long resolved, Map<EventType, Long> byType) {
        Map<EventType, Long> filled = zeroByType();
        filled.putAll(byType);
        return new EventSummaryResponse(
                unchecked + checked + resolved, unchecked, checked, resolved, Collections.unmodifiableMap(filled));
    }

    // EnumMap이라 JSON 키가 CONGESTION·SURGE·CAM_FAULT 순서로 나간다
    private static Map<EventType, Long> zeroByType() {
        Map<EventType, Long> byType = new EnumMap<>(EventType.class);
        for (EventType type : EventType.values()) {
            byType.put(type, 0L);
        }
        return byType;
    }
}
