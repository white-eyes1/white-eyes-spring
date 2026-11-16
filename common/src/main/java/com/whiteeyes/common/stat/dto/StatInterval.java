package com.whiteeyes.common.stat.dto;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.Period;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.time.temporal.TemporalAmount;
import java.util.Arrays;
import java.util.Optional;

/**
 * 통계 시계열의 단위(GET /api/v1/stats/series의 interval, conventions.md '통계 단위').
 * 1w는 월요일 시작, 1M은 달력 월, 1y는 달력 연도다.
 * 단위별 최대 조회 기간(conventions.md STATS_MAX_RANGE)은 환경변수가 아니라 코드 상수로 둔다(#25에서 정함).
 */
public enum StatInterval {

    /** 1분 통계(zone_stat_minute). 최대 1일. */
    MINUTE("1m", "1분", ChronoUnit.MINUTES, Duration.ofDays(1), "1일"),
    /** 시간별 통계(zone_stat_hourly). 최대 31일. */
    HOUR("1h", "1시간", ChronoUnit.HOURS, Duration.ofDays(31), "31일"),
    /** 일별 통계(zone_stat_daily). 최대 1년(달력 기준이라 윤년은 366일). */
    DAY("1d", "1일", ChronoUnit.DAYS, Period.ofYears(1), "1년"),
    /** 일별 통계를 주로 묶음(월요일 시작). 최대 3년. */
    WEEK("1w", "1주", ChronoUnit.WEEKS, Period.ofYears(3), "3년"),
    /** 일별 통계를 달력 월로 묶음. 최대 5년. */
    MONTH("1M", "1월", ChronoUnit.MONTHS, Period.ofYears(5), "5년"),
    /** 일별 통계를 달력 연도로 묶음. 최대 10년. */
    YEAR("1y", "1년", ChronoUnit.YEARS, Period.ofYears(10), "10년");

    // API 요청에서 쓰는 코드(1m, 1h, 1d, 1w, 1M, 1y)
    private final String code;
    private final String label;
    // 구간 하나의 길이를 나타내는 시간 단위. floor/next 계산에 쓴다
    private final ChronoUnit unit;
    // 허용되는 최대 조회 기간. 분·시간은 고정 길이(Duration), 일 이상은 달력 기준(Period)이라 타입이 다르다
    private final TemporalAmount maxRange;
    // 오류 메시지에 쓰는 최대 기간 표기('1일', '31일' 등)
    private final String maxRangeLabel;

    // enum 상수마다 요청 코드·표시 이름·시간 단위·최대 기간을 받아 둔다
    StatInterval(String code, String label, ChronoUnit unit, TemporalAmount maxRange, String maxRangeLabel) {
        this.code = code;
        this.label = label;
        this.unit = unit;
        this.maxRange = maxRange;
        this.maxRangeLabel = maxRangeLabel;
    }

    /** 요청 값(1m 등)으로 찾는다. 없는 값이면 빈 값. */
    public static Optional<StatInterval> fromCode(String code) {
        // 모르는 코드에 예외 대신 빈 값을 돌려주려고 valueOf를 쓰지 않고 직접 찾는다(1m과 1M이 달라서 대소문자도 구분한다)
        return Arrays.stream(values())
                .filter(interval -> interval.code.equals(code))
                .findFirst();
    }

    /** 시각이 속한 구간의 시작 시각. 주·월·연은 truncatedTo로 내릴 수 없어 날짜로 맞춘다. */
    public LocalDateTime floor(LocalDateTime time) {
        return switch (this) {
                // 분·시간·일은 해당 단위 아래 값을 잘라내면 구간 시작이 된다
            case MINUTE, HOUR, DAY -> time.truncatedTo(unit);
                // 주는 그 주의 월요일 0시가 시작이다(이미 월요일이면 그날)
            case WEEK -> time.toLocalDate()
                    .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                    .atStartOfDay();
                // 월은 1일 0시
            case MONTH -> time.toLocalDate().withDayOfMonth(1).atStartOfDay();
                // 연은 1월 1일 0시
            case YEAR -> time.toLocalDate().withDayOfYear(1).atStartOfDay();
        };
    }

    /** 다음 구간의 시작 시각. */
    public LocalDateTime next(LocalDateTime bucketStart) {
        // 월·연은 달력 기준으로 더해지므로 31일·윤년 같은 길이 차이를 직접 계산하지 않아도 된다
        return bucketStart.plus(1, unit);
    }

    /** 일별 통계를 읽어 묶는 단위인지(1w·1M·1y, FNC-C-012 처리 순서 3). */
    public boolean isRolledUpFromDaily() {
        return this == WEEK || this == MONTH || this == YEAR;
    }

    /** from 이상 to 미만이 최대 조회 기간을 넘는지. 최대 기간과 같으면 넘지 않는다. */
    public boolean isRangeTooLong(LocalDateTime from, LocalDateTime to) {
        // from에 최대 기간을 더한 시각이 to와 같으면 허용(넘을 때만 true)
        return to.isAfter(from.plus(maxRange));
    }

    // API 요청·응답에 쓰는 코드
    public String getCode() {
        return code;
    }

    // 화면·엑셀에 표시하는 한글 이름
    public String getLabel() {
        return label;
    }

    // 오류 메시지용 최대 기간 표기
    public String getMaxRangeLabel() {
        return maxRangeLabel;
    }
}
