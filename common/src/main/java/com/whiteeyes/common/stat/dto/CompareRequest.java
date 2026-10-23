package com.whiteeyes.common.stat.dto;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.Optional;

import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.validation.MapBindingResult;

import com.whiteeyes.common.stat.exception.InvalidRangeException;

/**
 * 기간 비교 조건(FNC-C-020 입력 항목). 기간은 날짜(yyyy-MM-dd)로 받고 양끝 날짜를 포함한다.
 * 기준 기간은 baseFrom·baseTo 또는 basePreset 중 하나로 정한다.
 */
public record CompareRequest(
        DashboardTargetType targetType, long targetId, ComparePeriod target, ComparePeriod base, int thresholdPct) {

    public static final int DEFAULT_THRESHOLD_PCT = 30;
    // 화면의 입력 범위(screens.md SCR-C-005 '비정상 기준 %')
    public static final int MIN_THRESHOLD_PCT = 5;
    public static final int MAX_THRESHOLD_PCT = 100;

    private static final String OBJECT_NAME = "compareRequest";
    // GlobalExceptionHandler의 형식 오류·필수 문구와 같은 문장
    private static final String INVALID_FORMAT_MESSAGE = "형식이 올바르지 않습니다.";
    private static final String REQUIRED_MESSAGE = "필수입니다.";
    private static final String BASE_REQUIRED_MESSAGE = "기준 기간을 지정해 주세요.";
    private static final String BASE_CONFLICT_MESSAGE = "기준 기간은 프리셋과 날짜 중 하나만 지정해 주세요.";
    private static final String THRESHOLD_RANGE_MESSAGE =
            "비정상 기준은 " + MIN_THRESHOLD_PCT + "~" + MAX_THRESHOLD_PCT + " 사이로 입력해 주세요.";
    private static final String REVERSED_MESSAGE = "끝 날짜는 시작 날짜보다 앞설 수 없습니다.";
    private static final String TOO_LONG_MESSAGE = "각 기간은 최대 1년까지 지정할 수 있습니다.";

    /**
     * 요청 값을 읽고 검사한다.
     *
     * @param thresholdPct 없으면 기본 30
     * @throws BindException 날짜·프리셋 형식, 기준 기간 지정 방식, 기준 % 범위가 틀리면(400 INVALID_INPUT, 칸별 오류)
     * @throws InvalidRangeException 끝 날짜가 시작보다 앞서거나 기간이 1년을 넘으면(400 INVALID_RANGE)
     */
    public static CompareRequest parse(
            DashboardTargetType targetType,
            long targetId,
            String fromText,
            String toText,
            String baseFromText,
            String baseToText,
            String basePresetText,
            Integer thresholdPct)
            throws BindException {
        MapBindingResult errors = new MapBindingResult(new HashMap<>(), OBJECT_NAME);
        Optional<LocalDate> from = parseDate(errors, "from", fromText);
        Optional<LocalDate> to = parseDate(errors, "to", toText);

        Optional<BasePreset> preset = Optional.empty();
        Optional<LocalDate> baseFrom = Optional.empty();
        Optional<LocalDate> baseTo = Optional.empty();
        boolean hasPreset = hasText(basePresetText);
        boolean hasBaseDates = hasText(baseFromText) || hasText(baseToText);
        if (hasPreset && hasBaseDates) {
            reject(errors, "basePreset", BASE_CONFLICT_MESSAGE);
        } else if (hasPreset) {
            preset = parsePreset(errors, basePresetText);
        } else if (hasBaseDates) {
            baseFrom = parseDate(errors, "baseFrom", baseFromText);
            baseTo = parseDate(errors, "baseTo", baseToText);
        } else {
            reject(errors, "basePreset", BASE_REQUIRED_MESSAGE);
        }

        int threshold = thresholdPct == null ? DEFAULT_THRESHOLD_PCT : thresholdPct;
        if (threshold < MIN_THRESHOLD_PCT || threshold > MAX_THRESHOLD_PCT) {
            reject(errors, "thresholdPct", THRESHOLD_RANGE_MESSAGE);
        }
        if (errors.hasErrors()) {
            throw new BindException(errors);
        }

        ComparePeriod target = checkedPeriod(from.get(), to.get());
        ComparePeriod base = preset.isPresent()
                ? target.minusDays(preset.get().getShiftDays())
                : checkedPeriod(baseFrom.get(), baseTo.get());
        return new CompareRequest(targetType, targetId, target, base, threshold);
    }

    // 1년 = 시작 날짜부터 1년 뒤 같은 날짜 전날까지(예: 2026-01-01 ~ 2026-12-31)
    private static ComparePeriod checkedPeriod(LocalDate from, LocalDate to) {
        if (to.isBefore(from)) {
            throw new InvalidRangeException(REVERSED_MESSAGE);
        }
        if (to.isAfter(from.plusYears(1).minusDays(1))) {
            throw new InvalidRangeException(TOO_LONG_MESSAGE);
        }
        return new ComparePeriod(from, to);
    }

    private static Optional<LocalDate> parseDate(MapBindingResult errors, String field, String text) {
        if (!hasText(text)) {
            reject(errors, field, REQUIRED_MESSAGE);
            return Optional.empty();
        }
        try {
            return Optional.of(LocalDate.parse(text));
        } catch (DateTimeParseException e) {
            reject(errors, field, INVALID_FORMAT_MESSAGE);
            return Optional.empty();
        }
    }

    private static Optional<BasePreset> parsePreset(MapBindingResult errors, String text) {
        try {
            return Optional.of(BasePreset.valueOf(text));
        } catch (IllegalArgumentException e) {
            reject(errors, "basePreset", INVALID_FORMAT_MESSAGE);
            return Optional.empty();
        }
    }

    private static boolean hasText(String text) {
        return text != null && !text.isBlank();
    }

    private static void reject(MapBindingResult errors, String field, String message) {
        errors.addError(new FieldError(OBJECT_NAME, field, message));
    }
}
