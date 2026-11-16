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

    // 검증 오류에 붙일 객체 이름
    private static final String OBJECT_NAME = "heatmapRequest";
    // GlobalExceptionHandler의 형식 오류 문구와 같은 문장
    private static final String INVALID_FORMAT_MESSAGE = "형식이 올바르지 않습니다.";
    private static final String TO_NOT_AFTER_FROM_MESSAGE = "끝 시각은 시작 시각보다 뒤여야 합니다.";
    private static final String TOO_LONG_MESSAGE = "히트맵은 최대 1년까지 조회할 수 있습니다.";

    /** 칠할 항목(conventions.md '통계 항목' 중 히트맵에 쓰는 것). */
    public enum Item {
        // 평균 인원
        COUNT,
        // 밀집도
        DENSITY,
        // 유입
        INFLOW,
        // 유출
        OUTFLOW
    }

    /** 요일 조건. */
    public enum DayType {
        // 모든 요일
        ALL,
        // 평일(월~금)
        WEEKDAY,
        // 주말(토·일)
        WEEKEND;

        /** 주어진 요일이 이 조건에 맞는지. ALL은 모든 요일, WEEKDAY는 월~금, WEEKEND는 토·일 */
        public boolean matches(DayOfWeek day) {
            // 토·일만 주말로 본다(공휴일은 고려하지 않는다)
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
            long regionId, String fromText, String toText, String itemText, String dayTypeText) throws BindException {
        // 오류를 모아 두었다가 한 번에 던진다(칸별 오류 응답)
        MapBindingResult errors = new MapBindingResult(new HashMap<>(), OBJECT_NAME);
        // 시각 형식(오프셋 붙은 ISO / 오프셋 없음 / 날짜만)은 통계 시계열 조회와 같은 파서를 재사용한다
        Optional<LocalDateTime> from = StatSeriesRequest.parseTime(fromText);
        if (from.isEmpty()) {
            reject(errors, "from", INVALID_FORMAT_MESSAGE);
        }
        Optional<LocalDateTime> to = StatSeriesRequest.parseTime(toText);
        if (to.isEmpty()) {
            reject(errors, "to", INVALID_FORMAT_MESSAGE);
        }
        // 끝이 시작과 같거나 앞서면 오류. 둘 다 읽힌 경우에만 비교한다
        if (from.isPresent() && to.isPresent() && !to.get().isAfter(from.get())) {
            reject(errors, "to", TO_NOT_AFTER_FROM_MESSAGE);
        }
        // 항목은 필수라 비어 있어도 parseEnum이 빈 값을 돌려주고 형식 오류가 된다
        Optional<Item> item = parseEnum(Item.class, itemText);
        if (item.isEmpty()) {
            reject(errors, "item", INVALID_FORMAT_MESSAGE);
        }
        // 요일 조건은 선택: 비우면 ALL(모든 요일)
        Optional<DayType> dayType = dayTypeText == null || dayTypeText.isBlank()
                ? Optional.of(DayType.ALL)
                : parseEnum(DayType.class, dayTypeText);
        if (dayType.isEmpty()) {
            reject(errors, "dayType", INVALID_FORMAT_MESSAGE);
        }
        // 칸별 오류가 있으면 먼저 던진다. 아래의 get()은 모두 값이 있다는 전제다
        if (errors.hasErrors()) {
            throw new BindException(errors);
        }
        // 히트맵은 최대 1년. 정확히 1년이면 허용한다
        if (to.get().isAfter(from.get().plusYears(1))) {
            throw new RangeTooLongException(TOO_LONG_MESSAGE);
        }
        return new HeatmapRequest(regionId, from.get(), to.get(), item.get(), dayType.get());
    }

    /** 이름이 정확히 같은 enum 상수를 찾는다. valueOf와 달리 예외 대신 빈 값을 돌려준다(대소문자 구분) */
    private static <E extends Enum<E>> Optional<E> parseEnum(Class<E> type, String text) {
        return Arrays.stream(type.getEnumConstants())
                .filter(value -> value.name().equals(text))
                .findFirst();
    }

    /** 칸(field) 하나의 오류를 errors에 추가한다 */
    private static void reject(MapBindingResult errors, String field, String message) {
        errors.addError(new FieldError(OBJECT_NAME, field, message));
    }
}
