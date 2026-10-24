package com.whiteeyes.admin.event.dto;

/** 이벤트 요약(api/public.md GET /api/v1/events/summary, FNC-A-036): 처리 상태별 건수. */
public record EventSummaryResponse(long total, long unchecked, long checked, long resolved) {

    public static EventSummaryResponse empty() {
        return new EventSummaryResponse(0, 0, 0, 0);
    }

    public static EventSummaryResponse of(long unchecked, long checked, long resolved) {
        return new EventSummaryResponse(unchecked + checked + resolved, unchecked, checked, resolved);
    }
}
