package com.whiteeyes.common.stat.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.whiteeyes.common.stat.entity.StatValues;

/** 시간별·일별 묶음 계산(FNC-A-029·030) */
class StatRollupCalculatorTest {

    @Test
    @DisplayName("count_avg·density는 sample_sec 가중 평균, 최대는 최대, 최소는 sample_sec>0인 행의 최소, 나머지는 합")
    void rollsUpByWeightedAverage() {
        // 손계산: (10×60 + 40×30) ÷ 90 = 20.00 / (0.1×60 + 0.4×30) ÷ 90 = 0.2000
        StatValues full = row("10.00", 12, 8, "0.1000", 1, 60, 100, 2);
        StatValues half = row("40.00", 50, 30, "0.4000", 2, 30, 50, 1);
        // 받은 초가 없는 행은 평균·최소에서 빠지고 합에는 0을 더한다
        StatValues gap = row("0.00", 0, 0, "0.0000", 0, 0, 0, 0);

        StatValues result = StatRollupCalculator.rollUp(List.of(full, half, gap)).orElseThrow();

        assertThat(result.countAvg()).isEqualByComparingTo("20.00");
        assertThat(result.density()).isEqualByComparingTo("0.2000");
        assertThat(result.countMax()).isEqualTo(50);
        assertThat(result.countMin()).isEqualTo(8);
        assertThat(result.inN()).isEqualTo(3);
        assertThat(result.outW()).isEqualTo(3);
        assertThat(result.dwellSumSec()).isEqualTo(150);
        assertThat(result.dwellCount()).isEqualTo(3);
        assertThat(result.sampleSec()).isEqualTo(90);
    }

    @Test
    @DisplayName("가중 평균은 소수 2자리(밀집도는 4자리) HALF_UP")
    void roundsHalfUp() {
        // (0.01×1 + 0.00×2) ÷ 3 = 0.00333… → 0.00 / (0.01×2 + 0.00×1) ÷ 3 = 0.00666… → 0.01
        StatValues a = row("0.01", 1, 0, "0.0001", 0, 2, 0, 0);
        StatValues b = row("0.00", 1, 0, "0.0000", 0, 1, 0, 0);

        StatValues result = StatRollupCalculator.rollUp(List.of(a, b)).orElseThrow();

        assertThat(result.countAvg()).isEqualByComparingTo("0.01");
        assertThat(result.density()).isEqualByComparingTo("0.0001");
    }

    @Test
    @DisplayName("받은 초가 있는 행이 하나도 없으면 묶지 않음")
    void emptyWhenNoSample() {
        assertThat(StatRollupCalculator.rollUp(List.of(row("0.00", 0, 0, "0.0000", 0, 0, 0, 0)))).isEmpty();
        assertThat(StatRollupCalculator.rollUp(List.of())).isEmpty();
    }

    @Test
    @DisplayName("INT 범위를 넘는 체류 시간 합도 BIGINT로 더함")
    void sumsDwellAsLong() {
        StatValues big = row("1.00", 1, 1, "0.0100", 0, 60, Integer.MAX_VALUE, 1);

        StatValues result = StatRollupCalculator.rollUp(List.of(big, big)).orElseThrow();

        assertThat(result.dwellSumSec()).isEqualTo(2L * Integer.MAX_VALUE);
    }

    private StatValues row(String avg, int max, int min, String density, int inOut, int sampleSec, long dwellSum,
            int dwellCount) {
        return new StatValues(new BigDecimal(avg), max, min, new BigDecimal(density),
                inOut, inOut, inOut, inOut, inOut, inOut, inOut, inOut, dwellSum, dwellCount, sampleSec);
    }
}
