package com.whiteeyes.common.stat.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;

import com.whiteeyes.common.exception.ErrorCode;
import com.whiteeyes.common.stat.exception.RangeTooLongException;

/** 통계 그래프 조회(FNC-C-012) 조건 읽기·검사 */
class StatSeriesRequestTest {

    @Test
    @DisplayName("FNC-C-012 오프셋이 붙은 시각은 KST로 바꾸고, 오프셋 없는 시각은 KST로, 날짜는 그날 0시로 읽는다")
    void parse_timeFormats() throws BindException {
        StatSeriesRequest withOffset = parse("2099-01-01T01:00:00Z", "2099-01-01T10:30:00+09:00");
        assertThat(withOffset.from()).isEqualTo(LocalDateTime.of(2099, 1, 1, 10, 0));
        assertThat(withOffset.to()).isEqualTo(LocalDateTime.of(2099, 1, 1, 10, 30));

        StatSeriesRequest local = parse("2099-01-01T10:00", "2099-01-01T10:05:30");
        assertThat(local.from()).isEqualTo(LocalDateTime.of(2099, 1, 1, 10, 0));
        assertThat(local.to()).isEqualTo(LocalDateTime.of(2099, 1, 1, 10, 5, 30));

        StatSeriesRequest dates = parse("2099-01-01", "2099-01-02");
        assertThat(dates.from()).isEqualTo(LocalDateTime.of(2099, 1, 1, 0, 0));
        assertThat(dates.to()).isEqualTo(LocalDateTime.of(2099, 1, 2, 0, 0));
        assertThat(dates.interval()).isEqualTo(StatInterval.MINUTE);
    }

    @Test
    @DisplayName("FNC-C-012 1분 단위는 1일까지 되고, 1일을 넘으면 RANGE_TOO_LONG")
    void parse_maxRange() throws BindException {
        assertThat(parse("2099-01-01T00:00", "2099-01-02T00:00").to()).isEqualTo(LocalDateTime.of(2099, 1, 2, 0, 0));

        assertThatThrownBy(() -> parse("2099-01-01T00:00", "2099-01-02T00:01"))
                .isInstanceOf(RangeTooLongException.class)
                .hasMessage("1분 단위는 최대 1일까지 조회할 수 있습니다.")
                .extracting("errorCode")
                .isEqualTo(ErrorCode.RANGE_TOO_LONG);
    }

    @Test
    @DisplayName("FNC-C-012 끝이 시작보다 늦지 않으면 to 칸 오류")
    void parse_toNotAfterFrom() {
        assertThatThrownBy(() -> parse("2099-01-01T10:00", "2099-01-01T10:00"))
                .isInstanceOfSatisfying(BindException.class, ex -> assertThat(ex.getFieldErrors())
                        .extracting(FieldError::getField, FieldError::getDefaultMessage)
                        .containsExactly(tuple("to", "끝 시각은 시작 시각보다 뒤여야 합니다.")));
    }

    @Test
    @DisplayName("FNC-C-012 모르는 단위와 읽을 수 없는 시각은 칸마다 형식 오류")
    void parse_invalidFormats() {
        assertThatThrownBy(() -> StatSeriesRequest.parse(StatTargetType.ZONE, 1L, "5m", "abc", "2099-13-01"))
                .isInstanceOfSatisfying(BindException.class, ex -> assertThat(ex.getFieldErrors())
                        .extracting(FieldError::getField)
                        .containsExactly("interval", "from", "to"));
    }

    private static StatSeriesRequest parse(String from, String to) throws BindException {
        return StatSeriesRequest.parse(StatTargetType.REGION, 1L, "1m", from, to);
    }
}
