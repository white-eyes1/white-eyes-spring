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
 * 방문 패턴 조건(FNC-C-028 입력 항목). 기간은 날짜(yyyy-MM-dd)로 받고 양끝 날짜를 포함한다(기간 비교 FNC-C-020과 같음).
 * 기간을 비우면 어제까지 28일이고, 최대 1년이다.
 */
public record VisitPatternRequest(DashboardTargetType targetType, long targetId, ComparePeriod period) {

    /** 기간을 비웠을 때의 날 수(어제까지 28일). */
    public static final int DEFAULT_DAYS = 28;

    private static final String OBJECT_NAME = "visitPatternRequest";
    // GlobalExceptionHandler의 형식 오류·필수 문구와 같은 문장
    private static final String INVALID_FORMAT_MESSAGE = "형식이 올바르지 않습니다.";
    private static final String REQUIRED_MESSAGE = "필수입니다.";
    // 기간 비교(CompareRequest)와 같은 문장
    private static final String REVERSED_MESSAGE = "끝 날짜는 시작 날짜보다 앞설 수 없습니다.";
    private static final String TOO_LONG_MESSAGE = "기간은 최대 1년까지 지정할 수 있습니다.";

    /**
     * 요청 값을 읽고 검사한다. from·to는 둘 다 주거나 둘 다 비운다.
     *
     * @param today 오늘(KST). 기간을 비우면 어제까지 28일
     * @throws BindException 날짜 형식이 틀리거나 한쪽만 주면(400 INVALID_INPUT, 칸별 오류)
     * @throws InvalidRangeException 끝 날짜가 시작보다 앞서거나 기간이 1년을 넘으면(400 INVALID_RANGE)
     */
    public static VisitPatternRequest parse(
            DashboardTargetType targetType, long targetId, String fromText, String toText, LocalDate today)
            throws BindException {
        if (!hasText(fromText) && !hasText(toText)) {
            LocalDate yesterday = today.minusDays(1);
            return new VisitPatternRequest(
                    targetType, targetId, new ComparePeriod(yesterday.minusDays(DEFAULT_DAYS - 1), yesterday));
        }
        MapBindingResult errors = new MapBindingResult(new HashMap<>(), OBJECT_NAME);
        Optional<LocalDate> from = parseDate(errors, "from", fromText);
        Optional<LocalDate> to = parseDate(errors, "to", toText);
        if (errors.hasErrors()) {
            throw new BindException(errors);
        }
        if (to.get().isBefore(from.get())) {
            throw new InvalidRangeException(REVERSED_MESSAGE);
        }
        if (to.get().isAfter(from.get().plusYears(1).minusDays(1))) {
            throw new InvalidRangeException(TOO_LONG_MESSAGE);
        }
        return new VisitPatternRequest(targetType, targetId, new ComparePeriod(from.get(), to.get()));
    }

    private static Optional<LocalDate> parseDate(MapBindingResult errors, String field, String text) {
        if (!hasText(text)) {
            errors.addError(new FieldError(OBJECT_NAME, field, REQUIRED_MESSAGE));
            return Optional.empty();
        }
        try {
            return Optional.of(LocalDate.parse(text));
        } catch (DateTimeParseException e) {
            errors.addError(new FieldError(OBJECT_NAME, field, INVALID_FORMAT_MESSAGE));
            return Optional.empty();
        }
    }

    private static boolean hasText(String text) {
        return text != null && !text.isBlank();
    }
}
