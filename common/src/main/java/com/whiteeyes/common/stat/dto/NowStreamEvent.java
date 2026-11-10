package com.whiteeyes.common.stat.dto;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * GET /api/v1/stats/stream 의 event: now 데이터(FNC-C-022). 5초마다 보내는 구독 지역 구역의 현재 인원.
 * 15초 안에 찍힌 신선한 값이 있는 구역만 담는다.
 */
public record NowStreamEvent(List<Item> items) {

    /** 구역 하나의 현재 인원. at은 그 인원이 찍힌 초(한국 시간 오프셋). */
    public record Item(Long zoneId, int currentCount, OffsetDateTime at) {}
}
