package com.whiteeyes.common.stat.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 지도 현황의 단위 하나(구역 또는 지역). 최근 5분 안에 통계가 없으면 countAvg·density·level·statAt이 null이다.
 *
 * @param centerLat 구역일 때만 내보낸다(api/public.md)
 * @param level 혼잡 단계 1~3(FNC-C-025)
 */
public record MapStatusItem(
        long id,
        String name,
        @JsonInclude(JsonInclude.Include.NON_NULL) BigDecimal centerLat,
        @JsonInclude(JsonInclude.Include.NON_NULL) BigDecimal centerLng,
        BigDecimal countAvg,
        BigDecimal density,
        Integer level,
        OffsetDateTime statAt,
        int todayEventCount,
        boolean ongoingEvent) {
}
