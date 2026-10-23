package com.whiteeyes.common.stat.dto;

import java.math.BigDecimal;

/**
 * 기간 비교표의 한 칸(요일 × 시간). 평균·증감률은 소수 첫째 자리까지다.
 *
 * @param weekday 1=월 … 7=일(ISO)
 * @param hour 0~23
 * @param targetAvg 대상 기간 그 칸의 평균 인원. 통계가 없으면 null
 * @param baseAvg 기준 기간 그 칸의 평균 인원. 통계가 없으면 null
 * @param rate 증감률(%). 기준 평균이 0이거나 어느 한쪽이 없으면 비교 불가라 null
 * @param abnormal |rate| ≥ 기준 %면 true. 비교 불가 칸은 false
 */
public record CompareCell(
        int weekday, int hour, BigDecimal targetAvg, BigDecimal baseAvg, BigDecimal rate, boolean abnormal) {
}
