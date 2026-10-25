package com.whiteeyes.common.stat.dto;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Optional;

import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.validation.MapBindingResult;

import com.whiteeyes.common.stat.exception.RangeTooLongException;

/**
 * 하위 지역 비교 조건(FNC-C-014 입력 항목). from 이상 to 미만이고, 시각 형식·단위별 최대 기간은 통계 시계열(FNC-C-012)과 같다.
 *
 * @param regionId 상위 지역 번호
 * @param item 겹쳐 그릴 항목. conventions.md '통계 항목'과 같은 목록이라 엑셀 항목({@link StatExportItem})을 그대로 쓴다
 */
public record StatChildrenRequest(
        long regionId, StatExportItem item, StatInterval interval, LocalDateTime from, LocalDateTime to) {

    private static final String OBJECT_NAME = "statChildrenRequest";
    // GlobalExceptionHandler의 형식 오류 문구와 같은 문장
    private static final String INVALID_FORMAT_MESSAGE = "형식이 올바르지 않습니다.";
    private static final String TO_NOT_AFTER_FROM_MESSAGE = "끝 시각은 시작 시각보다 뒤여야 합니다.";

    /**
     * 요청 값을 읽고 검사한다.
     *
     * @throws BindException 항목·단위·시각 형식이 틀리거나 끝이 시작보다 늦지 않으면(400 INVALID_INPUT, 칸별 오류)
     * @throws RangeTooLongException 단위별 최대 기간을 넘으면(400 RANGE_TOO_LONG)
     */
    public static StatChildrenRequest parse(
            long regionId, String itemCode, String intervalCode, String fromText, String toText)
            throws BindException {
        MapBindingResult errors = new MapBindingResult(new HashMap<>(), OBJECT_NAME);
        Optional<StatExportItem> item = StatExportItem.fromCode(itemCode);
        if (item.isEmpty()) {
            reject(errors, "item", INVALID_FORMAT_MESSAGE);
        }
        Optional<StatInterval> interval = StatInterval.fromCode(intervalCode);
        if (interval.isEmpty()) {
            reject(errors, "interval", INVALID_FORMAT_MESSAGE);
        }
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
        if (errors.hasErrors()) {
            throw new BindException(errors);
        }
        if (interval.get().isRangeTooLong(from.get(), to.get())) {
            throw new RangeTooLongException(interval.get());
        }
        return new StatChildrenRequest(regionId, item.get(), interval.get(), from.get(), to.get());
    }

    private static void reject(MapBindingResult errors, String field, String message) {
        errors.addError(new FieldError(OBJECT_NAME, field, message));
    }
}
