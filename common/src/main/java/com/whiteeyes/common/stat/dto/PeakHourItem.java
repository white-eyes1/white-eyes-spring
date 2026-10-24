package com.whiteeyes.common.stat.dto;

import java.math.BigDecimal;

/**
 * 피크 타임 한 줄(GET /api/v1/dashboard/peak-hours 응답, FNC-C-029).
 *
 * @param rank 1~3위
 * @param hour 0~23시
 * @param avgCount 그 시간대의 평균 인원
 */
public record PeakHourItem(int rank, int hour, BigDecimal avgCount) {
}
