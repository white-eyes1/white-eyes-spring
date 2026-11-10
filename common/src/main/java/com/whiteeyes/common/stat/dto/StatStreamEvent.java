package com.whiteeyes.common.stat.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * GET /api/v1/stats/stream 의 event: stat 데이터(FNC-C-022). 새로 저장된 1분의 구독 지역 구역 통계.
 *
 * @param statAt 그 1분의 시각(한국 시간 오프셋)
 */
public record StatStreamEvent(OffsetDateTime statAt, List<Item> items) {

    /** 구역 하나의 1분 통계. level은 혼잡 단계(1 정상 / 2 주의 / 3 위험). */
    public record Item(Long zoneId, BigDecimal countAvg, BigDecimal density, int level) {}
}
