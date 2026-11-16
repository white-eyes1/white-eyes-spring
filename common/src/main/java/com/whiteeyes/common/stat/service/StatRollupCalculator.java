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

    // 정적 메서드만 가진 유틸리티라 인스턴스를 만들지 못하게 막는다
    private StatRollupCalculator() {}

    /** 1분 통계 엔티티를 묶음 계산에 쓰는 값 묶음(StatValues)으로 바꾼다 */
    public static StatValues of(ZoneStatMinute minute) {
        return new StatValues(
                minute.getCountAvg(),
                minute.getCountMax(),
                minute.getCountMin(),
                minute.getDensity(),
                minute.getInN(),
                minute.getInE(),
                minute.getInS(),
                minute.getInW(),
                minute.getOutN(),
                minute.getOutE(),
                minute.getOutS(),
                minute.getOutW(),
                minute.getDwellSumSec(),
                minute.getDwellCount(),
                minute.getSampleSec());
    }

    /**
     * @param rows 한 구역의 하위 통계 행들
     * @return 묶은 값. 받은 초가 있는 행이 하나도 없으면 가중 평균을 낼 수 없어 비어 있다
     */
    public static Optional<StatValues> rollUp(List<StatValues> rows) {
        // 받은 초가 0인 행은 인원 0이 아니라 '측정 없음'이다. 평균·최소에 섞이면 값이 왜곡되므로 걸러낸다
        List<StatValues> sampled =
                rows.stream().filter(row -> row.sampleSec() > 0).toList();
        // 가중치(받은 초)의 합이 0이면 나눗셈이 불가능하다. 상위 행을 만들지 않도록 빈 값으로 알린다
        if (sampled.isEmpty()) {
            return Optional.empty();
        }
        // 가중치의 합. 합이 int를 넘을 수 있어 long으로 더한다
        long sampleSecSum = sampled.stream().mapToLong(StatValues::sampleSec).sum();
        // 평균 인원·밀집도에 (값 × 받은 초)를 누적한다. 부동소수 오차를 피하려고 BigDecimal을 쓴다
        BigDecimal weightedCount = BigDecimal.ZERO;
        BigDecimal weightedDensity = BigDecimal.ZERO;
        for (StatValues row : sampled) {
            // 받은 초가 많은 행일수록 평균에 더 크게 반영된다
            BigDecimal weight = BigDecimal.valueOf(row.sampleSec());
            weightedCount = weightedCount.add(row.countAvg().multiply(weight));
            weightedDensity = weightedDensity.add(row.density().multiply(weight));
        }
        // 누적한 합을 가중치 합으로 나누면 가중 평균이 된다(아래 반환값에서 나눈다)
        BigDecimal totalWeight = BigDecimal.valueOf(sampleSecSum);

        return Optional.of(new StatValues(
                // 평균 인원은 소수 둘째 자리(count_avg 칼럼과 같은 자릿수)
                weightedCount.divide(totalWeight, 2, RoundingMode.HALF_UP),
                // 최대는 받은 초가 0인 행까지 포함한 모든 행의 최댓값(받은 초가 0인 행의 최대는 보통 0이라 결과에 영향이 없다)
                rows.stream().mapToInt(StatValues::countMax).max().orElseThrow(),
                // 최소는 측정이 있는 행에서만 구한다. 측정 없는 행의 0이 끼면 항상 0이 되어 버린다
                sampled.stream().mapToInt(StatValues::countMin).min().orElseThrow(),
                // 밀집도는 소수 넷째 자리(density 칼럼과 같은 자릿수)
                weightedDensity.divide(totalWeight, 4, RoundingMode.HALF_UP),
                // 방향별 유입·유출과 체류 합·사람 수는 단순 합이다
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
                // 칼럼이 int라 범위를 넘으면 조용히 잘리지 않고 예외가 나도록 toIntExact를 쓴다
                Math.toIntExact(sampleSecSum)));
    }

    /** 한 칼럼의 합. 합이 int 범위를 넘으면 ArithmeticException으로 드러낸다 */
    private static int sum(List<StatValues> rows, ToIntFunction<StatValues> column) {
        return Math.toIntExact(rows.stream().mapToLong(column::applyAsInt).sum());
    }
}
