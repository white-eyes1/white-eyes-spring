package com.whiteeyes.common.stat.dto;

import java.math.BigDecimal;

/**
 * 시간대별 혼잡 단계의 한 칸(GET /api/v1/dashboard/hourly-levels 응답의 한 줄, FNC-C-018).
 *
 * @param hour 0~23시
 * @param level 혼잡 단계. 데이터 없는 시간은 null
 * @param density 평균 밀집도(명/㎡). 데이터 없는 시간은 null
 */
public record HourlyLevelItem(int hour, Integer level, BigDecimal density) {
}
