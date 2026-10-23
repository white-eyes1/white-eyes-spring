package com.whiteeyes.common.stat.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.whiteeyes.common.stat.dto.CompareCell;
import com.whiteeyes.common.stat.dto.CompareResponse;

// 2026-10-19는 월요일, 2026-09-21은 그보다 28일 앞선 월요일
/** 기간 비교 계산(FNC-C-020 처리 순서 2~4, 검수 기준 '비정상 칸 판정 경계') */
class CompareCalculatorTest {

    private static final LocalDateTime MON_09 = LocalDateTime.of(2026, 10, 19, 9, 0);
    private static final LocalDateTime BASE_MON_09 = LocalDateTime.of(2026, 9, 21, 9, 0);
    private static final int THRESHOLD = 30;

    @ParameterizedTest(name = "대상 {0} / 기준 100 → {1}%, 비정상 {2}")
    @CsvSource({
        "130, 30.0, true",
        "70, -30.0, true",
        "129.9, 29.9, false",
        "70.1, -29.9, false",
        // 화면에 보이는 증감률(반올림 후)로 판정한다
        "129.96, 30.0, true",
        "129.94, 29.9, false"
    })
    @DisplayName("FNC-C-020 |증감률| ≥ 기준 %면 비정상(경계 포함), 증가·감소 모두")
    void abnormalBoundary(String targetCount, String expectedRate, boolean expectedAbnormal) {
        CompareResponse response = CompareCalculator.compare(
                Map.of(MON_09, new BigDecimal(targetCount)), Map.of(BASE_MON_09, new BigDecimal("100")), THRESHOLD);

        CompareCell cell = cell(response, 1, 9);
        assertThat(cell.rate()).isEqualByComparingTo(expectedRate);
        assertThat(cell.abnormal()).isEqualTo(expectedAbnormal);
    }

    @Test
    @DisplayName("FNC-C-020 칸 평균은 같은 요일·시간 시각들의 평균이고 다른 요일·시간은 섞지 않는다")
    void averagesSameWeekdayAndHour() {
        Map<LocalDateTime, BigDecimal> target = new HashMap<>();
        target.put(MON_09, new BigDecimal("100"));
        target.put(MON_09.plusWeeks(1), new BigDecimal("201"));
        // 같은 월요일의 10시, 화요일 9시는 다른 칸
        target.put(MON_09.plusHours(1), new BigDecimal("999"));
        target.put(MON_09.plusDays(1), new BigDecimal("555"));

        CompareResponse response = CompareCalculator.compare(target, Map.of(), THRESHOLD);

        // (100 + 201) ÷ 2 = 150.5
        assertThat(cell(response, 1, 9).targetAvg()).isEqualByComparingTo("150.5");
        assertThat(cell(response, 1, 10).targetAvg()).isEqualByComparingTo("999.0");
        assertThat(cell(response, 2, 9).targetAvg()).isEqualByComparingTo("555.0");
    }

    @Test
    @DisplayName("FNC-C-020 기준 평균이 0이거나 어느 한쪽이 없는 칸은 비교 불가(rate null, 정상)")
    void notComparableCells() {
        Map<LocalDateTime, BigDecimal> target = Map.of(
                MON_09, new BigDecimal("50"),
                MON_09.plusHours(1), new BigDecimal("50"));
        Map<LocalDateTime, BigDecimal> base = Map.of(
                BASE_MON_09, BigDecimal.ZERO,
                BASE_MON_09.plusHours(2), new BigDecimal("80"));

        CompareResponse response = CompareCalculator.compare(target, base, THRESHOLD);

        CompareCell baseZero = cell(response, 1, 9);
        assertThat(baseZero.baseAvg()).isEqualByComparingTo("0.0");
        assertThat(baseZero.rate()).isNull();
        assertThat(baseZero.abnormal()).isFalse();
        CompareCell baseMissing = cell(response, 1, 10);
        assertThat(baseMissing.targetAvg()).isEqualByComparingTo("50.0");
        assertThat(baseMissing.baseAvg()).isNull();
        assertThat(baseMissing.rate()).isNull();
        CompareCell targetMissing = cell(response, 1, 11);
        assertThat(targetMissing.targetAvg()).isNull();
        assertThat(targetMissing.baseAvg()).isEqualByComparingTo("80.0");
        assertThat(targetMissing.rate()).isNull();
        assertThat(targetMissing.abnormal()).isFalse();
        assertThat(response.totalRate()).isNull();
    }

    @Test
    @DisplayName("FNC-C-020 전체 증감률은 비교 가능한 칸의 대상 평균들의 평균과 기준 평균들의 평균으로 계산한다")
    void totalRate() {
        Map<LocalDateTime, BigDecimal> target = Map.of(
                MON_09, new BigDecimal("150"),
                MON_09.plusHours(1), new BigDecimal("60"),
                // 기준이 0인 칸은 전체에서 빠진다
                MON_09.plusHours(2), new BigDecimal("1000"));
        Map<LocalDateTime, BigDecimal> base = Map.of(
                BASE_MON_09, new BigDecimal("100"),
                BASE_MON_09.plusHours(1), new BigDecimal("50"),
                BASE_MON_09.plusHours(2), BigDecimal.ZERO);

        CompareResponse response = CompareCalculator.compare(target, base, THRESHOLD);

        // ((150 + 60) ÷ 2 − (100 + 50) ÷ 2) ÷ ((100 + 50) ÷ 2) × 100 = 40.0. 칸 증감률(+50, +20)의 단순 평균 35와 다르다
        assertThat(response.totalRate()).isEqualByComparingTo("40.0");
    }

    @Test
    @DisplayName("FNC-C-020 칸은 월 0시부터 일 23시까지 168개를 모두 돌려준다(ISO 요일 1=월)")
    void allCellsInOrder() {
        CompareResponse response = CompareCalculator.compare(Map.of(), Map.of(), THRESHOLD);

        assertThat(response.cells()).hasSize(168);
        assertThat(response.cells().get(0)).extracting(CompareCell::weekday, CompareCell::hour).containsExactly(1, 0);
        assertThat(response.cells().get(167)).extracting(CompareCell::weekday, CompareCell::hour).containsExactly(7, 23);
        assertThat(response.cells()).allSatisfy(cell -> {
            assertThat(cell.targetAvg()).isNull();
            assertThat(cell.rate()).isNull();
        });
        assertThat(response.totalRate()).isNull();
    }

    private static CompareCell cell(CompareResponse response, int weekday, int hour) {
        return response.cells().get((weekday - 1) * 24 + hour);
    }
}
