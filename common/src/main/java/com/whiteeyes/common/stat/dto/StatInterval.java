package com.whiteeyes.common.stat.dto;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Optional;

/**
 * 통계 시계열의 단위(GET /api/v1/stats/series의 interval, conventions.md '통계 단위').
 * 단위별 최대 조회 기간(conventions.md STATS_MAX_RANGE)은 환경변수가 아니라 코드 상수로 둔다(#25에서 정함).
 */
public enum StatInterval {

    /** 1분 통계(zone_stat_minute). 최대 1일. */
    MINUTE("1m", "1분", Duration.ofDays(1), "1일");

    private final String code;
    private final String label;
    private final Duration maxRange;
    private final String maxRangeLabel;

    StatInterval(String code, String label, Duration maxRange, String maxRangeLabel) {
        this.code = code;
        this.label = label;
        this.maxRange = maxRange;
        this.maxRangeLabel = maxRangeLabel;
    }

    /** 요청 값(1m 등)으로 찾는다. 없는 값이면 빈 값. */
    public static Optional<StatInterval> fromCode(String code) {
        return Arrays.stream(values()).filter(interval -> interval.code.equals(code)).findFirst();
    }

    /** 시각이 속한 구간의 시작 시각. */
    public LocalDateTime floor(LocalDateTime time) {
        return time.truncatedTo(ChronoUnit.MINUTES);
    }

    /** 다음 구간의 시작 시각. */
    public LocalDateTime next(LocalDateTime bucketStart) {
        return bucketStart.plusMinutes(1);
    }

    /** from 이상 to 미만이 최대 조회 기간을 넘는지. 최대 기간과 같으면 넘지 않는다. */
    public boolean isRangeTooLong(LocalDateTime from, LocalDateTime to) {
        return to.isAfter(from.plus(maxRange));
    }

    public String getCode() {
        return code;
    }

    public String getLabel() {
        return label;
    }

    public String getMaxRangeLabel() {
        return maxRangeLabel;
    }
}
