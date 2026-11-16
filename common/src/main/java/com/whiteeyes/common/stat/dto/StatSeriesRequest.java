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

    // 오프셋이 붙은 시각을 한국 시간으로 맞출 때 쓴다. 서버는 통계를 모두 KST 기준으로 저장한다
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    // 검증 오류에 붙일 객체 이름
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
        // 오류를 모아 두었다가 한 번에 던진다(칸별 오류 응답)
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
        // 끝이 시작과 같거나 앞서면 오류. 둘 다 읽힌 경우에만 비교한다
        if (from.isPresent() && to.isPresent() && !to.get().isAfter(from.get())) {
            reject(errors, "to", TO_NOT_AFTER_FROM_MESSAGE);
        }
        // 형식 오류가 있으면 먼저 던진다. 아래에서는 모든 값이 있다는 전제로 get()을 쓴다
        if (errors.hasErrors()) {
            throw new BindException(errors);
        }
        // 단위별 최대 조회 기간을 넘으면 RANGE_TOO_LONG
        if (interval.get().isRangeTooLong(from.get(), to.get())) {
            throw new RangeTooLongException(interval.get());
        }
        return new StatSeriesRequest(targetType, targetId, interval.get(), from.get(), to.get());
    }

    // 엑셀 다운로드 조건(StatExportRequest)도 같은 형식으로 읽는다
    static Optional<LocalDateTime> parseTime(String text) {
        try {
            // 1순위: 오프셋이 붙은 시각. 같은 순간을 KST 벽시계 시각으로 바꿔 저장 기준과 맞춘다
            return Optional.of(OffsetDateTime.parse(text).atZoneSameInstant(KST).toLocalDateTime());
        } catch (DateTimeParseException ignored) {
            // 오프셋이 없으면 아래 형식으로 읽는다
        }
        try {
            // 2순위: 오프셋 없는 시각은 이미 KST로 간주한다
            return Optional.of(LocalDateTime.parse(text));
        } catch (DateTimeParseException ignored) {
            // 날짜만 온 경우를 아래에서 읽는다
        }
        try {
            // 3순위: 날짜만 오면 그날 0시. 이것도 안 되면 형식 오류로 빈 값을 돌려준다
            return Optional.of(LocalDate.parse(text).atStartOfDay());
        } catch (DateTimeParseException ignored) {
            return Optional.empty();
        }
    }

    /** 칸(field) 하나의 오류를 errors에 추가한다 */
    private static void reject(MapBindingResult errors, String field, String message) {
        errors.addError(new FieldError(OBJECT_NAME, field, message));
    }
}
