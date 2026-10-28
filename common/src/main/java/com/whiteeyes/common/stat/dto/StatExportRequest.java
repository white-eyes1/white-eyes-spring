package com.whiteeyes.common.stat.dto;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.Period;
import java.time.temporal.TemporalAmount;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.validation.MapBindingResult;

import com.whiteeyes.common.stat.exception.RangeTooLongException;
import com.whiteeyes.common.stat.exception.TooManyRowsException;

/**
 * 통계 엑셀 다운로드 조건(FNC-C-023 입력 항목). from 이상 to 미만이고, 시각은 KST다.
 * 시각 형식은 통계 시계열 조회(StatSeriesRequest)와 같다.
 *
 * <p>단위별 최대 기간(conventions.md EXPORT_MAX_RANGE)은 STATS_MAX_RANGE처럼 코드 상수로 둔다.
 *
 * @param targetId 구역·지역 번호. 전체(ALL)면 null
 * @param items 고른 항목(요청 순서, 중복 없음). 항목마다 시트 하나
 * @param sum 지역을 합산해 한 줄로 받는지. 지역(REGION)일 때만 true일 수 있고, 아니면 지역도 소속 구역별 행이다
 */
public record StatExportRequest(
        StatExportScope scope,
        Long targetId,
        StatInterval interval,
        LocalDateTime from,
        LocalDateTime to,
        List<StatExportItem> items,
        boolean sum) {

    /** 한 시트에 쓸 수 있는 최대 행 수(conventions.md EXPORT_MAX_RANGE '예상 행 수 100만 이하'). */
    public static final long MAX_ROWS = 1_000_000L;

    private static final String OBJECT_NAME = "statExportRequest";
    // GlobalExceptionHandler의 형식 오류 문구와 같은 문장
    private static final String INVALID_FORMAT_MESSAGE = "형식이 올바르지 않습니다.";
    private static final String TO_NOT_AFTER_FROM_MESSAGE = "끝 시각은 시작 시각보다 뒤여야 합니다.";
    private static final String TARGET_REQUIRED_MESSAGE = "대상을 골라 주세요.";
    private static final String ITEMS_REQUIRED_MESSAGE = "항목을 하나 이상 골라 주세요.";

    /**
     * 요청 값을 읽고 검사한다. 전체(ALL)는 targetId를 쓰지 않고, 지역 합산(sum)은 지역(REGION)일 때만 쓴다.
     *
     * @param itemCodes 항목 코드. 같은 이름을 여러 번 보내거나 쉼표로 이어 보내도 된다
     * @throws BindException 대상·단위·시각·항목이 없거나 형식이 틀리거나 끝이 시작보다 늦지 않으면(400 INVALID_INPUT)
     * @throws RangeTooLongException 단위별 최대 기간을 넘으면(400 RANGE_TOO_LONG)
     */
    public static StatExportRequest parse(
            StatExportScope scope,
            Long targetId,
            String intervalCode,
            String fromText,
            String toText,
            List<String> itemCodes,
            boolean sum)
            throws BindException {
        MapBindingResult errors = new MapBindingResult(new HashMap<>(), OBJECT_NAME);
        if (scope != StatExportScope.ALL && targetId == null) {
            reject(errors, "targetId", TARGET_REQUIRED_MESSAGE);
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
        Optional<List<StatExportItem>> items = parseItems(itemCodes);
        if (items.isEmpty()) {
            reject(errors, "items", INVALID_FORMAT_MESSAGE);
        } else if (items.get().isEmpty()) {
            reject(errors, "items", ITEMS_REQUIRED_MESSAGE);
        }
        if (errors.hasErrors()) {
            throw new BindException(errors);
        }
        MaxRange maxRange = MaxRange.of(interval.get());
        if (to.get().isAfter(from.get().plus(maxRange.amount()))) {
            throw new RangeTooLongException(
                    interval.get().getLabel() + " 단위는 최대 " + maxRange.label() + "까지 내려받을 수 있습니다.");
        }
        return new StatExportRequest(
                scope,
                scope == StatExportScope.ALL ? null : targetId,
                interval.get(),
                from.get(),
                to.get(),
                items.get(),
                scope == StatExportScope.REGION && sum);
    }

    /**
     * 한 시트에 들어갈 시각 수. from이 구간 중간이면 그 구간 시작부터 센다(통계 시계열과 같은 규칙, #25).
     */
    public long pointCount() {
        long count = 0;
        for (LocalDateTime at = interval.floor(from); at.isBefore(to); at = interval.next(at)) {
            count++;
        }
        return count;
    }

    /**
     * 한 시트의 예상 행 수(대상 줄 수 × 시각 수)가 최대를 넘지 않는지 확인한다(FNC-C-023 처리 순서 1).
     * 최대와 같으면 허용한다.
     *
     * @param seriesCount 시트에 쓸 대상 줄 수. 구역·지역 합산은 1, 지역 기본(구역별)·전체는 구역 수
     * @return 예상 행 수
     * @throws TooManyRowsException 최대 행 수를 넘으면(400 TOO_MANY_ROWS)
     */
    public long checkRowLimit(long seriesCount) {
        long rows = seriesCount * pointCount();
        if (rows > MAX_ROWS) {
            throw new TooManyRowsException(rows, MAX_ROWS);
        }
        return rows;
    }

    // 알 수 없는 코드가 하나라도 있으면 빈 값. 없으면 빈 목록
    private static Optional<List<StatExportItem>> parseItems(List<String> itemCodes) {
        Set<StatExportItem> items = new LinkedHashSet<>();
        if (itemCodes != null) {
            for (String joined : itemCodes) {
                for (String code : joined.split(",")) {
                    if (code.isBlank()) {
                        continue;
                    }
                    Optional<StatExportItem> item = StatExportItem.fromCode(code.trim());
                    if (item.isEmpty()) {
                        return Optional.empty();
                    }
                    items.add(item.get());
                }
            }
        }
        return Optional.of(List.copyOf(items));
    }

    private static void reject(MapBindingResult errors, String field, String message) {
        errors.addError(new FieldError(OBJECT_NAME, field, message));
    }

    /** 단위별 최대 기간(conventions.md EXPORT_MAX_RANGE): 1m 7일, 1h 93일, 1d 이상 10년. 최대 기간과 같으면 허용한다. */
    private record MaxRange(TemporalAmount amount, String label) {

        static MaxRange of(StatInterval interval) {
            return switch (interval) {
                case MINUTE -> new MaxRange(Duration.ofDays(7), "7일");
                case HOUR -> new MaxRange(Duration.ofDays(93), "93일");
                case DAY, WEEK, MONTH, YEAR -> new MaxRange(Period.ofYears(10), "10년");
            };
        }
    }
}
