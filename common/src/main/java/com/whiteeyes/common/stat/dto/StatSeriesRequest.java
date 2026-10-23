package com.whiteeyes.common.stat.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.Optional;

import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.validation.MapBindingResult;

import com.whiteeyes.common.stat.exception.RangeTooLongException;

/**
 * 통계 시계열 조회 조건(FNC-C-012 입력 항목). from 이상 to 미만이고, 시각은 KST다.
 *
 * <p>from·to는 오프셋이 붙은 ISO 시각(예: 2026-10-21T00:00:00+09:00), 오프셋 없는 시각, 날짜(그날 0시)를 받는다
 * (conventions.md '시각 형식'·'기간 조회'). 오프셋이 다르면 KST로 바꾼다.
 */
public record StatSeriesRequest(
        StatTargetType targetType, long targetId, StatInterval interval, LocalDateTime from, LocalDateTime to) {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final String OBJECT_NAME = "statSeriesRequest";
    // GlobalExceptionHandler의 형식 오류 문구와 같은 문장
    private static final String INVALID_FORMAT_MESSAGE = "형식이 올바르지 않습니다.";
    private static final String TO_NOT_AFTER_FROM_MESSAGE = "끝 시각은 시작 시각보다 뒤여야 합니다.";

    /**
     * 요청 값을 읽고 검사한다.
     *
     * @throws BindException 단위·시각 형식이 틀리거나 끝이 시작보다 늦지 않으면(400 INVALID_INPUT, 칸별 오류)
     * @throws RangeTooLongException 단위별 최대 기간을 넘으면(400 RANGE_TOO_LONG)
     */
    public static StatSeriesRequest parse(
            StatTargetType targetType, long targetId, String intervalCode, String fromText, String toText)
            throws BindException {
        MapBindingResult errors = new MapBindingResult(new HashMap<>(), OBJECT_NAME);
        Optional<StatInterval> interval = StatInterval.fromCode(intervalCode);
        if (interval.isEmpty()) {
            reject(errors, "interval", INVALID_FORMAT_MESSAGE);
        }
        Optional<LocalDateTime> from = parseTime(fromText);
        if (from.isEmpty()) {
            reject(errors, "from", INVALID_FORMAT_MESSAGE);
        }
        Optional<LocalDateTime> to = parseTime(toText);
        if (to.isEmpty()) {
            reject(errors, "to", INVALID_FORMAT_MESSAGE);
        }
        if (from.isPresent() && to.isPresent() && !to.get().isAfter(from.get())) {
            reject(errors, "to", TO_NOT_AFTER_FROM_MESSAGE);
        }
        if (errors.hasErrors()) {
            throw new BindException(errors);
        }
        if (interval.get().isRangeTooLong(from.get(), to.get())) {
            throw new RangeTooLongException(interval.get());
        }
        return new StatSeriesRequest(targetType, targetId, interval.get(), from.get(), to.get());
    }

    // 엑셀 다운로드 조건(StatExportRequest)도 같은 형식으로 읽는다
    static Optional<LocalDateTime> parseTime(String text) {
        try {
            return Optional.of(OffsetDateTime.parse(text).atZoneSameInstant(KST).toLocalDateTime());
        } catch (DateTimeParseException ignored) {
            // 오프셋이 없으면 아래 형식으로 읽는다
        }
        try {
            return Optional.of(LocalDateTime.parse(text));
        } catch (DateTimeParseException ignored) {
            // 날짜만 온 경우를 아래에서 읽는다
        }
        try {
            return Optional.of(LocalDate.parse(text).atStartOfDay());
        } catch (DateTimeParseException ignored) {
            return Optional.empty();
        }
    }

    private static void reject(MapBindingResult errors, String field, String message) {
        errors.addError(new FieldError(OBJECT_NAME, field, message));
    }
}
