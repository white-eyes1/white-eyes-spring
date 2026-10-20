package com.whiteeyes.admin.stat.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.whiteeyes.common.stat.entity.ZoneStatMinute;

class MinuteStatCalculatorTest {

    private static final long ZONE_ID = 3L;
    private static final LocalDateTime MINUTE = LocalDateTime.of(2026, 10, 12, 14, 3);

    @Test
    @DisplayName("FNC-A-028 손으로 만든 60초 입력으로 값 검증")
    void calculatesHandMadeSixtySeconds() {
        // 인원: 0~29초 20명, 30~59초 25명. 10초만 31명(최대), 45초만 12명(최저)
        //   합 = 20×29 + 31 + 25×29 + 12 = 1348 → 평균 1348 ÷ 60 = 22.4666… → 22.47
        // 유입: 0·10·20·30·40·50초에 북쪽 1명씩(6), 15초에 동쪽 2명
        // 유출: 5·25·45초에 남쪽 1명씩(3), 59초에 서쪽 4명
        // 체류: 10초에 12.4초·30.0초, 40초에 7.3초 → 합 49.7 → 50, 3명
        MinuteAccumulator accumulator = new MinuteAccumulator(ZONE_ID, MINUTE);
        for (int second = 0; second < 60; second++) {
            accumulator.put(MINUTE.withSecond(second), handMadeResult(second));
        }

        ZoneStatMinute stat = MinuteStatCalculator.calculate(accumulator, new BigDecimal("150.00"));

        assertThat(stat.getZoneId()).isEqualTo(ZONE_ID);
        assertThat(stat.getStatAt()).isEqualTo(MINUTE);
        assertThat(stat.getCountAvg()).isEqualByComparingTo("22.47");
        assertThat(stat.getCountMax()).isEqualTo(31);
        assertThat(stat.getCountMin()).isEqualTo(12);
        // 22.47 ÷ 150 = 0.1498
        assertThat(stat.getDensity()).isEqualByComparingTo("0.1498");
        assertThat(List.of(stat.getInN(), stat.getInE(), stat.getInS(), stat.getInW())).containsExactly(6, 2, 0, 0);
        assertThat(List.of(stat.getOutN(), stat.getOutE(), stat.getOutS(), stat.getOutW()))
                .containsExactly(0, 0, 3, 4);
        assertThat(stat.getDwellSumSec()).isEqualTo(50);
        assertThat(stat.getDwellCount()).isEqualTo(3);
        assertThat(stat.getSampleSec()).isEqualTo((short) 60);
    }

    @Test
    @DisplayName("FNC-A-028 반올림은 HALF_UP이고 density는 저장하는 count_avg로 계산")
    void roundsHalfUpAndUsesStoredCountAvgForDensity() {
        // 8초만 들어온 분: 인원 1, 0, 0, 0, 0, 0, 0, 0 → 평균 0.125 → 0.13(HALF_EVEN이면 0.12)
        // density: 0.13 ÷ 3 = 0.04333… → 0.0433 (반올림 전 평균으로 나누면 0.0417)
        // 체류: 1.25 + 1.25 = 2.5 → 3
        MinuteAccumulator accumulator = new MinuteAccumulator(ZONE_ID, MINUTE);
        accumulator.put(MINUTE.withSecond(0), result(1, DirectionCounts.ZERO, DirectionCounts.ZERO, List.of(1.25, 1.25)));
        for (int second = 1; second < 8; second++) {
            accumulator.put(MINUTE.withSecond(second), result(0, DirectionCounts.ZERO, DirectionCounts.ZERO, List.of()));
        }

        ZoneStatMinute stat = MinuteStatCalculator.calculate(accumulator, new BigDecimal("3.00"));

        assertThat(stat.getCountAvg()).isEqualByComparingTo("0.13");
        assertThat(stat.getDensity()).isEqualByComparingTo("0.0433");
        assertThat(stat.getDwellSumSec()).isEqualTo(3);
        assertThat(stat.getSampleSec()).isEqualTo((short) 8);
    }

    private SecondResult handMadeResult(int second) {
        int personCount = second < 30 ? 20 : 25;
        if (second == 10) {
            personCount = 31;
        } else if (second == 45) {
            personCount = 12;
        }

        DirectionCounts in = DirectionCounts.ZERO;
        if (second % 10 == 0) {
            in = new DirectionCounts(1, 0, 0, 0);
        } else if (second == 15) {
            in = new DirectionCounts(0, 2, 0, 0);
        }

        DirectionCounts out = DirectionCounts.ZERO;
        if (second == 5 || second == 25 || second == 45) {
            out = new DirectionCounts(0, 0, 1, 0);
        } else if (second == 59) {
            out = new DirectionCounts(0, 0, 0, 4);
        }

        List<Double> dwellSec = List.of();
        if (second == 10) {
            dwellSec = List.of(12.4, 30.0);
        } else if (second == 40) {
            dwellSec = List.of(7.3);
        }
        return result(personCount, in, out, dwellSec);
    }

    private SecondResult result(int personCount, DirectionCounts in, DirectionCounts out, List<Double> dwellSec) {
        return new SecondResult(personCount, in, out, dwellSec);
    }
}
