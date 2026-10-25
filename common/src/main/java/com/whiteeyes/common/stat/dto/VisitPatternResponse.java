package com.whiteeyes.common.stat.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * GET /api/v1/dashboard/visit-pattern 응답(FNC-C-028).
 *
 * @param cells 168칸 전부(월 0시부터 일 23시까지 순서). 데이터 없는 칸은 avgCount가 null
 * @param weekdayAvg 평일(월~금) 0~23시 평균 인원 24개. 데이터 없는 시간은 null
 * @param weekendAvg 주말(토·일) 0~23시 평균 인원 24개
 */
public record VisitPatternResponse(List<Cell> cells, List<BigDecimal> weekdayAvg, List<BigDecimal> weekendAvg) {

    /**
     * @param weekday 1=월 ~ 7=일
     * @param hour 0~23
     * @param avgCount 그 요일·시간의 평균 인원
     */
    public record Cell(int weekday, int hour, BigDecimal avgCount) {
    }
}
