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
 * @param items 고른 항목(요청 순서, 중복 없음). 엑셀 열 순서는 요청 순서가 아니라 StatExportItem 선언 순서
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
    // 엑셀 한 시트의 행 수 한계(약 100만, 엑셀 시트 자체의 한계도 이 근처다)
    public static final long MAX_ROWS = 1_000_000L;

    // 검증 오류에 붙일 객체 이름
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
        // 오류를 모아 두었다가 한 번에 던진다(칸별 오류 응답)
        MapBindingResult errors = new MapBindingResult(new HashMap<>(), OBJECT_NAME);
        // 전체(ALL)가 아닌데 대상 번호가 없으면 어떤 구역·지역인지 알 수 없다
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
        // 빈 값이면 모르는 코드가 있다는 뜻(형식 오류), 빈 목록이면 하나도 안 골랐다는 뜻(필수 오류)이다
        Optional<List<StatExportItem>> items = parseItems(itemCodes);
        if (items.isEmpty()) {
            reject(errors, "items", INVALID_FORMAT_MESSAGE);
        } else if (items.get().isEmpty()) {
            reject(errors, "items", ITEMS_REQUIRED_MESSAGE);
        }
        // 형식 오류가 있으면 먼저 던진다. 아래에서는 모든 값이 있다는 전제로 get()을 쓴다
        if (errors.hasErrors()) {
            throw new BindException(errors);
        }
        // 단위별 최대 기간(1분 7일, 1시간 93일, 일 이상 10년)을 넘는지 검사한다
        MaxRange maxRange = MaxRange.of(interval.get());
        if (to.get().isAfter(from.get().plus(maxRange.amount()))) {
            throw new RangeTooLongException(
                    interval.get().getLabel() + " 단위는 최대 " + maxRange.label() + "까지 내려받을 수 있습니다.");
        }
        return new StatExportRequest(
                scope,
                // 전체 범위면 대상 번호는 의미가 없으니 null로 정리해 저장한다
                scope == StatExportScope.ALL ? null : targetId,
                interval.get(),
                from.get(),
                to.get(),
                items.get(),
                // 합산은 지역일 때만 유효하다. 구역·전체에서 sum=true가 와도 무시한다
                scope == StatExportScope.REGION && sum);
    }

    /**
     * 한 시트에 들어갈 시각 수. from이 구간 중간이면 그 구간 시작부터 센다(통계 시계열과 같은 규칙, #25).
     */
    public long pointCount() {
        // 구간 시작 시각을 하나씩 세어 간다. 월·연 단위는 길이가 달라 나눗셈으로 구하지 않고 직접 센다
        long count = 0;
        // from이 구간 중간이어도 그 구간의 시작부터 센다(시계열 응답과 같은 점 개수가 되도록)
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
        // 행 수 = 대상 줄 수 × 시각 수. 곱이 크면 엑셀을 만들기 전에 막아 서버 메모리를 지킨다
        long rows = seriesCount * pointCount();
        if (rows > MAX_ROWS) {
            throw new TooManyRowsException(rows, MAX_ROWS);
        }
        return rows;
    }

    // 알 수 없는 코드가 하나라도 있으면 빈 값. 없으면 빈 목록
    private static Optional<List<StatExportItem>> parseItems(List<String> itemCodes) {
        // 중복을 없애면서 요청 순서를 유지한다
        Set<StatExportItem> items = new LinkedHashSet<>();
        if (itemCodes != null) {
            // 같은 파라미터를 여러 번 보내도, "COUNT,DENSITY"처럼 쉼표로 이어 보내도 받아 준다
            for (String joined : itemCodes) {
                for (String code : joined.split(",")) {
                    // 쉼표가 연달아 있거나 끝에 붙은 빈 조각은 무시한다
                    if (code.isBlank()) {
                        continue;
                    }
                    Optional<StatExportItem> item = StatExportItem.fromCode(code.trim());
                    if (item.isEmpty()) {
                        // 알 수 없는 코드가 하나라도 있으면 전체를 오류로 본다
                        return Optional.empty();
                    }
                    items.add(item.get());
                }
            }
        }
        return Optional.of(List.copyOf(items));
    }

    /** 칸(field) 하나의 오류를 errors에 추가한다 */
    private static void reject(MapBindingResult errors, String field, String message) {
        errors.addError(new FieldError(OBJECT_NAME, field, message));
    }

    /** 단위별 최대 기간(conventions.md EXPORT_MAX_RANGE): 1m 7일, 1h 93일, 1d 이상 10년. 최대 기간과 같으면 허용한다. */
    private record MaxRange(TemporalAmount amount, String label) {

        /** 단위에 맞는 최대 기간을 돌려준다. 분·시간은 일수, 일 이상은 달력 기준 10년이다 */
        static MaxRange of(StatInterval interval) {
            return switch (interval) {
                case MINUTE -> new MaxRange(Duration.ofDays(7), "7일");
                case HOUR -> new MaxRange(Duration.ofDays(93), "93일");
                case DAY, WEEK, MONTH, YEAR -> new MaxRange(Period.ofYears(10), "10년");
            };
        }
    }
}
