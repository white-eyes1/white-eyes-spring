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

    private static final int DAYS = 7;
    private static final int HOURS = 24;
    private static final int RESULT_SCALE = 1;
    // 반올림 전 중간 값의 자릿수. 결과 자릿수(1)보다 충분히 크게 둔다
    private static final int WORK_SCALE = 6;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private CompareCalculator() {
    }

    /**
     * @param target 대상 기간의 시각(정시)별 인원
     * @param base 기준 기간의 시각(정시)별 인원
     * @param thresholdPct 비정상 기준 %. |증감률| ≥ 이 값이면 비정상
     */
    public static CompareResponse compare(
            Map<LocalDateTime, BigDecimal> target, Map<LocalDateTime, BigDecimal> base, int thresholdPct) {
        BigDecimal[] targetAvgs = averageByCell(target);
        BigDecimal[] baseAvgs = averageByCell(base);
        BigDecimal threshold = BigDecimal.valueOf(thresholdPct);

        List<CompareCell> cells = new ArrayList<>(DAYS * HOURS);
        BigDecimal comparableTargetSum = BigDecimal.ZERO;
        BigDecimal comparableBaseSum = BigDecimal.ZERO;
        for (int i = 0; i < DAYS * HOURS; i++) {
            BigDecimal targetAvg = targetAvgs[i];
            BigDecimal baseAvg = baseAvgs[i];
            BigDecimal rate = null;
            if (isComparable(targetAvg, baseAvg)) {
                rate = rate(targetAvg, baseAvg);
                comparableTargetSum = comparableTargetSum.add(targetAvg);
                comparableBaseSum = comparableBaseSum.add(baseAvg);
            }
            boolean abnormal = rate != null && rate.abs().compareTo(threshold) >= 0;
            cells.add(new CompareCell(
                    i / HOURS + 1, i % HOURS, roundOrNull(targetAvg), roundOrNull(baseAvg), rate, abnormal));
        }
        // 비교 가능한 칸 수가 같으므로 평균끼리의 증감률은 합끼리의 증감률과 같다
        BigDecimal totalRate = comparableBaseSum.signum() > 0 ? rate(comparableTargetSum, comparableBaseSum) : null;
        return new CompareResponse(cells, totalRate);
    }

    // 칸 번호 = (요일 - 1) × 24 + 시. 통계가 없는 칸은 null
    private static BigDecimal[] averageByCell(Map<LocalDateTime, BigDecimal> countByHour) {
        BigDecimal[] sums = new BigDecimal[DAYS * HOURS];
        int[] counts = new int[DAYS * HOURS];
        countByHour.forEach((at, count) -> {
            int index = cellIndex(at.getDayOfWeek(), at.getHour());
            sums[index] = sums[index] == null ? count : sums[index].add(count);
            counts[index]++;
        });
        BigDecimal[] averages = new BigDecimal[DAYS * HOURS];
        for (int i = 0; i < averages.length; i++) {
            if (counts[i] > 0) {
                averages[i] = sums[i].divide(BigDecimal.valueOf(counts[i]), WORK_SCALE, RoundingMode.HALF_UP);
            }
        }
        return averages;
    }

    private static int cellIndex(DayOfWeek dayOfWeek, int hour) {
        return (dayOfWeek.getValue() - 1) * HOURS + hour;
    }

    // 기준 평균이 0이거나 어느 한쪽이 없으면 비교 불가(FNC-C-020 업무 규칙)
    private static boolean isComparable(BigDecimal targetAvg, BigDecimal baseAvg) {
        return targetAvg != null && baseAvg != null && baseAvg.signum() > 0;
    }

    private static BigDecimal rate(BigDecimal target, BigDecimal base) {
        return target.subtract(base).multiply(HUNDRED).divide(base, RESULT_SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal roundOrNull(BigDecimal value) {
        return value == null ? null : value.setScale(RESULT_SCALE, RoundingMode.HALF_UP);
    }
}
