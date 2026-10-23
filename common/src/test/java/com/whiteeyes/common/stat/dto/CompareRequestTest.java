package com.whiteeyes.common.stat.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;

import com.whiteeyes.common.stat.exception.InvalidRangeException;

/** 기간 비교 조건 검사(FNC-C-020 입력 항목·업무 규칙·예외) */
class CompareRequestTest {

    private static final DashboardTargetType REGION = DashboardTargetType.REGION;

    @Test
    @DisplayName("FNC-C-020 프리셋 지난달은 28일, 지난해 같은 기간은 364일 앞으로 옮긴다")
    void presets() throws BindException {
        CompareRequest lastMonth = parse("2026-10-15", "2026-10-21", null, null, "LAST_MONTH", null);
        assertThat(lastMonth.target()).isEqualTo(period("2026-10-15", "2026-10-21"));
        assertThat(lastMonth.base()).isEqualTo(period("2026-09-17", "2026-09-23"));
        assertThat(lastMonth.thresholdPct()).isEqualTo(30);

        CompareRequest lastYear = parse("2026-10-15", "2026-10-21", null, null, "LAST_YEAR", 50);
        assertThat(lastYear.base()).isEqualTo(period("2025-10-16", "2025-10-22"));
        assertThat(lastYear.base().from().getDayOfWeek()).isEqualTo(lastYear.target().from().getDayOfWeek());
        assertThat(lastYear.thresholdPct()).isEqualTo(50);
    }

    @Test
    @DisplayName("FNC-C-020 직접 지정한 기준 기간을 쓰고, 기간은 양끝 날짜를 포함한다")
    void customBase() throws BindException {
        CompareRequest request = parse("2026-10-15", "2026-10-21", "2026-01-01", "2026-01-31", null, null);

        assertThat(request.base()).isEqualTo(period("2026-01-01", "2026-01-31"));
        assertThat(request.target().startAt()).isEqualTo(LocalDate.parse("2026-10-15").atStartOfDay());
        assertThat(request.target().endAt()).isEqualTo(LocalDate.parse("2026-10-22").atStartOfDay());
    }

    @Test
    @DisplayName("FNC-C-020 각 기간은 최대 1년(같으면 허용), 넘거나 뒤집히면 INVALID_RANGE")
    void rangeLimits() throws BindException {
        parse("2026-01-01", "2026-12-31", "2025-01-01", "2025-12-31", null, null);
        parse("2026-10-21", "2026-10-21", null, null, "LAST_MONTH", null);

        assertThatThrownBy(() -> parse("2026-01-01", "2027-01-01", null, null, "LAST_MONTH", null))
                .isInstanceOf(InvalidRangeException.class)
                .hasMessage("각 기간은 최대 1년까지 지정할 수 있습니다.");
        assertThatThrownBy(() -> parse("2026-10-21", "2026-10-20", null, null, "LAST_MONTH", null))
                .isInstanceOf(InvalidRangeException.class)
                .hasMessage("끝 날짜는 시작 날짜보다 앞설 수 없습니다.");
        assertThatThrownBy(() -> parse("2026-10-15", "2026-10-21", "2025-01-01", "2026-01-01", null, null))
                .isInstanceOf(InvalidRangeException.class);
        assertThatThrownBy(() -> parse("2026-10-15", "2026-10-21", "2026-01-31", "2026-01-01", null, null))
                .isInstanceOf(InvalidRangeException.class);
    }

    @ParameterizedTest(name = "기준 % {0}")
    @ValueSource(ints = {4, 101, 0, -30})
    @DisplayName("FNC-C-020 비정상 기준 %는 5~100만 받는다")
    void thresholdOutOfRange(int threshold) {
        assertThatThrownBy(() -> parse("2026-10-15", "2026-10-21", null, null, "LAST_MONTH", threshold))
                .isInstanceOfSatisfying(BindException.class, e -> assertThat(fieldOf(e)).isEqualTo("thresholdPct"));
    }

    @Test
    @DisplayName("FNC-C-020 기준 %는 경계 5와 100을 받는다")
    void thresholdBoundary() throws BindException {
        assertThat(parse("2026-10-15", "2026-10-21", null, null, "LAST_MONTH", 5).thresholdPct()).isEqualTo(5);
        assertThat(parse("2026-10-15", "2026-10-21", null, null, "LAST_MONTH", 100).thresholdPct()).isEqualTo(100);
    }

    @Test
    @DisplayName("FNC-C-020 기준 기간은 프리셋과 날짜 중 하나로만 정한다")
    void baseSpecification() {
        assertThatThrownBy(() -> parse("2026-10-15", "2026-10-21", null, null, null, null))
                .isInstanceOfSatisfying(BindException.class, e -> assertThat(fieldOf(e)).isEqualTo("basePreset"));
        assertThatThrownBy(() -> parse("2026-10-15", "2026-10-21", "2026-01-01", "2026-01-07", "LAST_MONTH", null))
                .isInstanceOfSatisfying(BindException.class, e -> assertThat(fieldOf(e)).isEqualTo("basePreset"));
        assertThatThrownBy(() -> parse("2026-10-15", "2026-10-21", "2026-01-01", null, null, null))
                .isInstanceOfSatisfying(BindException.class, e -> assertThat(fieldOf(e)).isEqualTo("baseTo"));
        assertThatThrownBy(() -> parse("2026-10-15", "2026-10-21", null, null, "LAST_WEEK", null))
                .isInstanceOfSatisfying(BindException.class, e -> assertThat(fieldOf(e)).isEqualTo("basePreset"));
    }

    @Test
    @DisplayName("FNC-C-020 날짜는 yyyy-MM-dd만 받는다")
    void dateFormat() {
        assertThatThrownBy(() -> parse("2026-10-15T00:00", "2026/10/21", null, null, "LAST_MONTH", null))
                .isInstanceOfSatisfying(BindException.class, e -> assertThat(e.getFieldErrors())
                        .extracting(FieldError::getField)
                        .containsExactly("from", "to"));
    }

    private static CompareRequest parse(
            String from, String to, String baseFrom, String baseTo, String preset, Integer threshold)
            throws BindException {
        return CompareRequest.parse(REGION, 1L, from, to, baseFrom, baseTo, preset, threshold);
    }

    private static ComparePeriod period(String from, String to) {
        return new ComparePeriod(LocalDate.parse(from), LocalDate.parse(to));
    }

    private static String fieldOf(BindException e) {
        assertThat(e.getFieldErrors()).hasSize(1);
        return e.getFieldErrors().get(0).getField();
    }
}
