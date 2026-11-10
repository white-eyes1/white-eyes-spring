package com.whiteeyes.common.stat.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** 통계 그래프 조회(FNC-C-012) 단위별 구간 계산. 1w는 월요일 시작, 1M은 달력 월, 1y는 달력 연도(conventions.md '통계 단위') */
class StatIntervalTest {

    // 2099-01-05는 월요일, 2096년은 윤년
    @ParameterizedTest(name = "{0} {1} → 구간 {2}, 다음 {3}")
    @CsvSource({
        "MINUTE, 2099-01-07T15:30:45, 2099-01-07T15:30, 2099-01-07T15:31",
        "HOUR,   2099-01-07T15:30,    2099-01-07T15:00, 2099-01-07T16:00",
        "DAY,    2099-01-07T15:30,    2099-01-07T00:00, 2099-01-08T00:00",
        "WEEK,   2099-01-07T15:30,    2099-01-05T00:00, 2099-01-12T00:00",
        "WEEK,   2099-01-05T00:00,    2099-01-05T00:00, 2099-01-12T00:00",
        "WEEK,   2099-01-11T23:59,    2099-01-05T00:00, 2099-01-12T00:00",
        "WEEK,   2099-01-01T12:00,    2098-12-29T00:00, 2099-01-05T00:00",
        "MONTH,  2099-01-31T23:59,    2099-01-01T00:00, 2099-02-01T00:00",
        "MONTH,  2096-02-29T10:00,    2096-02-01T00:00, 2096-03-01T00:00",
        "YEAR,   2099-12-31T23:59,    2099-01-01T00:00, 2100-01-01T00:00",
        "YEAR,   2096-02-29T10:00,    2096-01-01T00:00, 2097-01-01T00:00"
    })
    @DisplayName("FNC-C-012 시각이 속한 구간의 시작과 다음 구간(주는 월요일, 월말·윤년·연말 경계)")
    void floorAndNext(StatInterval interval, LocalDateTime time, LocalDateTime bucket, LocalDateTime next) {
        assertThat(interval.floor(time)).isEqualTo(bucket);
        assertThat(interval.next(bucket)).isEqualTo(next);
    }
}
