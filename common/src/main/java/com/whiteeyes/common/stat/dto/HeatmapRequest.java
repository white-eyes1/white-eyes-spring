package com.whiteeyes.common.stat.dto;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Optional;

import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.validation.MapBindingResult;

import com.whiteeyes.common.stat.exception.RangeTooLongException;

/**
 * 시간대별 히트맵 조건(FNC-C-011 입력 항목). from 이상 to 미만이고, 시각 형식은 통계 시계열(StatSeriesRequest)과 같다. 기간은 최대 1년.
 */
public record HeatmapRequest(long regionId, LocalDateTime from, LocalDateTime to, Item item, DayType dayType) {

    private static final String OBJECT_NAME = "heatmapRequest";
    // GlobalExceptionHandler의 형식 오류 문구와 같은 문장
    private static final String INVALID_FORMAT_MESSAGE = "형식이 올바르지 않습니다.";
    private static final String TO_NOT_AFTER_FROM_MESSAGE = "끝 시각은 시작 시각보다 뒤여야 합니다.";
    private static final String TOO_LONG_MESSAGE = "히트맵은 최대 1년까지 조회할 수 있습니다.";

    /** 칠할 항목(conventions.md '통계 항목' 중 히트맵에 쓰는 것). */
    public enum Item {
        COUNT,
        DENSITY,
        INFLOW,
        OUTFLOW
    }

    /** 요일 조건. */
    public enum DayType {
        ALL,
        WEEKDAY,
        WEEKEND;

        public boolean matches(DayOfWeek day) {
            boolean isWeekend = day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY;
            return switch (this) {
                case ALL -> true;
                case WEEKDAY -> !isWeekend;
                case WEEKEND -> isWeekend;
            };
        }
    }

    /**
     * 요청 값을 읽고 검사한다.
     *
     * @param dayTypeText 비우면 ALL
     * @throws BindException 시각·항목·요일 형식이 틀리거나 끝이 시작보다 늦지 않으면(400 INVALID_INPUT, 칸별 오류)
     * @throws RangeTooLongException 기간이 1년을 넘으면(400 RANGE_TOO_LONG)
     */
    public static HeatmapRequest parse(
            long regionId, String fromText, String toText, String itemText, String dayTypeText)
            throws BindException {
        MapBindingResult errors = new MapBindingResult(new HashMap<>(), OBJECT_NAME);
        Optional<LocalDateTime> from = StatSeriesRequest.parseTime(fromText);
        if (from.isEmpty()) {
            reject(errors, "from", INVALID_FORMAT_MESSAGE);
        }
        Optional<LocalDateTime> to = StatSeriesRequest.parseTime(toText);
        if (to.isEmpty()) {
            reject(errors, "to", INVALID_FORMAT_MESSAGE);
        }
        if (from.isPresent() && to.isPresent() && !to.get().isAfter(from.get())) {
            reject(errors, "to", TO_NOT_AFTER_FROM_MESSAGE);
        }
        Optional<Item> item = parseEnum(Item.class, itemText);
        if (item.isEmpty()) {
            reject(errors, "item", INVALID_FORMAT_MESSAGE);
        }
        Optional<DayType> dayType = dayTypeText == null || dayTypeText.isBlank()
                ? Optional.of(DayType.ALL)
                : parseEnum(DayType.class, dayTypeText);
        if (dayType.isEmpty()) {
            reject(errors, "dayType", INVALID_FORMAT_MESSAGE);
        }
        if (errors.hasErrors()) {
            throw new BindException(errors);
        }
        if (to.get().isAfter(from.get().plusYears(1))) {
            throw new RangeTooLongException(TOO_LONG_MESSAGE);
        }
        return new HeatmapRequest(regionId, from.get(), to.get(), item.get(), dayType.get());
    }

    private static <E extends Enum<E>> Optional<E> parseEnum(Class<E> type, String text) {
        return Arrays.stream(type.getEnumConstants()).filter(value -> value.name().equals(text)).findFirst();
    }

    private static void reject(MapBindingResult errors, String field, String message) {
        errors.addError(new FieldError(OBJECT_NAME, field, message));
    }
}
