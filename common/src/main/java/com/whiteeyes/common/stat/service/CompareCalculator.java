package com.whiteeyes.common.stat.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.whiteeyes.common.stat.dto.CompareCell;
import com.whiteeyes.common.stat.dto.CompareResponse;

/**
 * 두 기간의 시각별 인원을 요일 × 시간 칸으로 모아 비교한다(FNC-C-020 처리 순서 2~4).
 *
 * <p>칸 평균은 그 칸에 해당하는 시각들(통계가 있는 시각만)의 평균이다. 증감률은 반올림 전 평균으로 계산하고,
 * 평균·증감률을 소수 첫째 자리에서 HALF_UP으로 맞춘 뒤 비정상 여부는 화면에 보이는 증감률로 판정한다.
 * 전체 증감률은 비교 가능한 칸만 모아 (대상 평균들의 평균 − 기준 평균들의 평균) ÷ 기준 평균들의 평균 × 100이다
 * (명세에 공식이 없어 정한 것, #26).
 */
public final class CompareCalculator {

    // 한 주의 요일 수
    private static final int DAYS = 7;
    private static final int HOURS = 24;
    // 결과로 내보내는 평균·증감률의 소수 자릿수
    private static final int RESULT_SCALE = 1;
    // 반올림 전 중간 값의 자릿수. 결과 자릿수(1)보다 충분히 크게 둔다
    private static final int WORK_SCALE = 6;
    // 비율을 %로 바꾸는 값
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    // 정적 메서드만 가진 유틸리티라 인스턴스를 만들지 못하게 막는다
    private CompareCalculator() {}

    /**
     * @param target 대상 기간의 시각(정시)별 인원
     * @param base 기준 기간의 시각(정시)별 인원
     * @param thresholdPct 비정상 기준 %. |증감률| ≥ 이 값이면 비정상
     */
    public static CompareResponse compare(
            Map<LocalDateTime, BigDecimal> target, Map<LocalDateTime, BigDecimal> base, int thresholdPct) {
        // 대상·기준 기간 각각 168칸(요일 7 × 시간 24) 평균을 구한다. 통계가 없는 칸은 null
        BigDecimal[] targetAvgs = averageByCell(target);
        BigDecimal[] baseAvgs = averageByCell(base);
        BigDecimal threshold = BigDecimal.valueOf(thresholdPct);

        // 168칸을 월요일 0시부터 일요일 23시 순서로 차례로 만든다
        List<CompareCell> cells = new ArrayList<>(DAYS * HOURS);
        // 전체 증감률은 비교 가능한 칸만으로 구하려고, 그 칸들의 평균 합을 따로 모은다
        BigDecimal comparableTargetSum = BigDecimal.ZERO;
        BigDecimal comparableBaseSum = BigDecimal.ZERO;
        for (int i = 0; i < DAYS * HOURS; i++) {
            BigDecimal targetAvg = targetAvgs[i];
            BigDecimal baseAvg = baseAvgs[i];
            BigDecimal rate = null;
            // 비교 가능한 칸만 증감률을 구하고 전체 합에 더한다. 아니면 rate는 null로 둔다
            if (isComparable(targetAvg, baseAvg)) {
                rate = rate(targetAvg, baseAvg);
                comparableTargetSum = comparableTargetSum.add(targetAvg);
                comparableBaseSum = comparableBaseSum.add(baseAvg);
            }
            // 화면에 보이는(반올림된) 증감률로 판정한다. 증가·감소 모두 절댓값으로 기준과 비교한다
            boolean abnormal = rate != null && rate.abs().compareTo(threshold) >= 0;
            cells.add(new CompareCell(
                    // 칸 번호 i에서 요일(1=월 ~ 7=일)과 시각(0~23)을 거꾸로 계산한다
                    i / HOURS + 1, i % HOURS, roundOrNull(targetAvg), roundOrNull(baseAvg), rate, abnormal));
        }
        // 비교 가능한 칸 수가 같으므로 평균끼리의 증감률은 합끼리의 증감률과 같다
        // 기준 합이 0이거나 비교 가능한 칸이 없으면 0으로 나누게 되므로 null(비교 불가)로 둔다
        BigDecimal totalRate = comparableBaseSum.signum() > 0 ? rate(comparableTargetSum, comparableBaseSum) : null;
        return new CompareResponse(cells, totalRate);
    }

    // 칸 번호 = (요일 - 1) × 24 + 시. 통계가 없는 칸은 null
    private static BigDecimal[] averageByCell(Map<LocalDateTime, BigDecimal> countByHour) {
        // 칸별 합계와 건수를 따로 모았다가 마지막에 나눠 평균을 낸다
        BigDecimal[] sums = new BigDecimal[DAYS * HOURS];
        int[] counts = new int[DAYS * HOURS];
        countByHour.forEach((at, count) -> {
            // 시각을 (요일, 시)로 바꿔 칸 번호를 구한다. 같은 칸에 여러 날짜의 시각이 쌓인다
            int index = cellIndex(at.getDayOfWeek(), at.getHour());
            // 처음 들어오는 값이면 그대로 두고, 아니면 더한다(null을 0으로 시작하면 '통계 없음'과 구분되지 않는다)
            sums[index] = sums[index] == null ? count : sums[index].add(count);
            counts[index]++;
        });
        BigDecimal[] averages = new BigDecimal[DAYS * HOURS];
        for (int i = 0; i < averages.length; i++) {
            // 한 건도 없는 칸은 평균을 내지 않고 null로 남긴다(0명이 아니라 통계 없음)
            if (counts[i] > 0) {
                // 중간 평균은 결과보다 넉넉한 자릿수로 두어, 증감률 계산 때의 오차를 줄인다
                averages[i] = sums[i].divide(BigDecimal.valueOf(counts[i]), WORK_SCALE, RoundingMode.HALF_UP);
            }
        }
        return averages;
    }

    private static int cellIndex(DayOfWeek dayOfWeek, int hour) {
        // 월요일(1)부터 0번 칸 묶음이 되도록 요일에서 1을 뺀다
        return (dayOfWeek.getValue() - 1) * HOURS + hour;
    }

    // 기준 평균이 0이거나 어느 한쪽이 없으면 비교 불가(FNC-C-020 업무 규칙)
    private static boolean isComparable(BigDecimal targetAvg, BigDecimal baseAvg) {
        return targetAvg != null && baseAvg != null && baseAvg.signum() > 0;
    }

    private static BigDecimal rate(BigDecimal target, BigDecimal base) {
        // 증감률(%) = (대상 - 기준) ÷ 기준 × 100. 기준이 0보다 커야 하므로 호출 전에 isComparable로 확인한다
        return target.subtract(base).multiply(HUNDRED).divide(base, RESULT_SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal roundOrNull(BigDecimal value) {
        // 응답용 평균은 소수 첫째 자리로 반올림한다
        return value == null ? null : value.setScale(RESULT_SCALE, RoundingMode.HALF_UP);
    }
}
