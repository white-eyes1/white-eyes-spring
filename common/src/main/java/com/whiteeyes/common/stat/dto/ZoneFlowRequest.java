package com.whiteeyes.common.stat.dto;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Optional;

import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.validation.MapBindingResult;

import com.whiteeyes.common.stat.exception.InvalidRangeException;

/**
 * 구역 간 이동 흐름 조건(FNC-C-010 입력 항목). from 이상 to 미만이고, 시각 형식은 통계 시계열(StatSeriesRequest)과 같다.
 */
public record ZoneFlowRequest(long regionId, LocalDateTime from, LocalDateTime to) {

    private static final String OBJECT_NAME = "zoneFlowRequest";
    // GlobalExceptionHandler의 형식 오류 문구와 같은 문장
    private static final String INVALID_FORMAT_MESSAGE = "형식이 올바르지 않습니다.";
    private static final String TO_NOT_AFTER_FROM_MESSAGE = "끝 시각은 시작 시각보다 뒤여야 합니다.";

    /**
     * 요청 값을 읽고 검사한다.
     *
     * @throws BindException 시각 형식이 틀리면(400 INVALID_INPUT, 칸별 오류)
     * @throws InvalidRangeException 끝이 시작보다 늦지 않으면(400 INVALID_RANGE, FNC-C-010 예외 상황 '기간 오류')
     */
    public static ZoneFlowRequest parse(long regionId, String fromText, String toText) throws BindException {
        MapBindingResult errors = new MapBindingResult(new HashMap<>(), OBJECT_NAME);
        Optional<LocalDateTime> from = StatSeriesRequest.parseTime(fromText);
        if (from.isEmpty()) {
            errors.addError(new FieldError(OBJECT_NAME, "from", INVALID_FORMAT_MESSAGE));
        }
        Optional<LocalDateTime> to = StatSeriesRequest.parseTime(toText);
        if (to.isEmpty()) {
            errors.addError(new FieldError(OBJECT_NAME, "to", INVALID_FORMAT_MESSAGE));
        }
        if (errors.hasErrors()) {
            throw new BindException(errors);
        }
        if (!to.get().isAfter(from.get())) {
            throw new InvalidRangeException(TO_NOT_AFTER_FROM_MESSAGE);
        }
        return new ZoneFlowRequest(regionId, from.get(), to.get());
    }
}
