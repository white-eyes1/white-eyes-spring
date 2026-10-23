package com.whiteeyes.common.stat.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;
import java.util.function.ToIntFunction;

import com.whiteeyes.common.stat.entity.StatValues;
import com.whiteeyes.common.stat.entity.ZoneStatMinute;

/**
 * 하위 통계 여러 행을 상위 한 행으로 묶는다(FNC-A-029 처리 순서 2, FNC-A-030 '시간별과 같은 계산').
 * 1분 → 시간, 시간 → 일을 같은 방식으로 묶으므로 입력은 {@link StatValues}로 통일한다.
 *
 * <p>count_avg·density는 sample_sec 가중 평균이다(끊긴 구간이 평균을 낮추지 않도록).
 * count_min은 sample_sec가 0보다 큰 행에서만 구하고, 나머지는 합·최대다.
 * 반올림은 HALF_UP이고 BigDecimal로 계산한다.
 */
public final class StatRollupCalculator {

    private StatRollupCalculator() {
    }

    public static StatValues of(ZoneStatMinute minute) {
        return new StatValues(minute.getCountAvg(), minute.getCountMax(), minute.getCountMin(), minute.getDensity(),
                minute.getInN(), minute.getInE(), minute.getInS(), minute.getInW(),
                minute.getOutN(), minute.getOutE(), minute.getOutS(), minute.getOutW(),
                minute.getDwellSumSec(), minute.getDwellCount(), minute.getSampleSec());
    }

    /**
     * @param rows 한 구역의 하위 통계 행들
     * @return 묶은 값. 받은 초가 있는 행이 하나도 없으면 가중 평균을 낼 수 없어 비어 있다
     */
    public static Optional<StatValues> rollUp(List<StatValues> rows) {
        List<StatValues> sampled = rows.stream().filter(row -> row.sampleSec() > 0).toList();
        if (sampled.isEmpty()) {
            return Optional.empty();
        }
        long sampleSecSum = sampled.stream().mapToLong(StatValues::sampleSec).sum();
        BigDecimal weightedCount = BigDecimal.ZERO;
        BigDecimal weightedDensity = BigDecimal.ZERO;
        for (StatValues row : sampled) {
            BigDecimal weight = BigDecimal.valueOf(row.sampleSec());
            weightedCount = weightedCount.add(row.countAvg().multiply(weight));
            weightedDensity = weightedDensity.add(row.density().multiply(weight));
        }
        BigDecimal totalWeight = BigDecimal.valueOf(sampleSecSum);

        return Optional.of(new StatValues(
                weightedCount.divide(totalWeight, 2, RoundingMode.HALF_UP),
                rows.stream().mapToInt(StatValues::countMax).max().orElseThrow(),
                sampled.stream().mapToInt(StatValues::countMin).min().orElseThrow(),
                weightedDensity.divide(totalWeight, 4, RoundingMode.HALF_UP),
                sum(rows, StatValues::inN),
                sum(rows, StatValues::inE),
                sum(rows, StatValues::inS),
                sum(rows, StatValues::inW),
                sum(rows, StatValues::outN),
                sum(rows, StatValues::outE),
                sum(rows, StatValues::outS),
                sum(rows, StatValues::outW),
                rows.stream().mapToLong(StatValues::dwellSumSec).sum(),
                sum(rows, StatValues::dwellCount),
                Math.toIntExact(sampleSecSum)));
    }

    private static int sum(List<StatValues> rows, ToIntFunction<StatValues> column) {
        return Math.toIntExact(rows.stream().mapToLong(column::applyAsInt).sum());
    }
}
