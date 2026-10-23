package com.whiteeyes.common.stat.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;

import com.whiteeyes.common.exception.ErrorCode;
import com.whiteeyes.common.stat.exception.RangeTooLongException;
import com.whiteeyes.common.stat.exception.TooManyRowsException;

/** 통계 엑셀 다운로드(FNC-C-023) 조건 읽기·검사 */
class StatExportRequestTest {

    private static final List<String> COUNT = List.of("COUNT");

    @Test
    @DisplayName("FNC-C-023 시각은 통계 그래프와 같은 형식(오프셋은 KST로, 날짜는 그날 0시)으로 읽는다")
    void parse_timeFormats() throws BindException {
        StatExportRequest request = StatExportRequest.parse(
                StatExportScope.ZONE, 1L, "1h", "2099-01-01T01:00:00Z", "2099-01-02", COUNT, false);

        assertThat(request.from()).isEqualTo(LocalDateTime.of(2099, 1, 1, 10, 0));
        assertThat(request.to()).isEqualTo(LocalDateTime.of(2099, 1, 2, 0, 0));
        assertThat(request.interval()).isEqualTo(StatInterval.HOUR);
        assertThat(request.targetId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("FNC-C-023 항목은 여러 번 보내도, 쉼표로 이어 보내도 되고 요청 순서대로 중복 없이 남는다")
    void parse_items() throws BindException {
        StatExportRequest repeated = StatExportRequest.parse(
                StatExportScope.ZONE, 1L, "1h", "2099-01-01", "2099-01-02",
                List.of("DWELL", "COUNT", "DWELL"), false);
        assertThat(repeated.items()).containsExactly(StatExportItem.DWELL, StatExportItem.COUNT);

        StatExportRequest joined = StatExportRequest.parse(
                StatExportScope.ZONE, 1L, "1h", "2099-01-01", "2099-01-02",
                List.of("COUNT,DENSITY, INFLOW", "OUTFLOW"), false);
        assertThat(joined.items()).containsExactly(
                StatExportItem.COUNT, StatExportItem.DENSITY, StatExportItem.INFLOW, StatExportItem.OUTFLOW);
    }

    @Test
    @DisplayName("FNC-C-023 예외 상황: 항목 없음은 400(items 칸 문구), 모르는 항목은 형식 오류")
    void parse_itemsRequired() {
        assertThatThrownBy(() -> StatExportRequest.parse(
                        StatExportScope.ZONE, 1L, "1h", "2099-01-01", "2099-01-02", List.of(), false))
                .isInstanceOfSatisfying(BindException.class, ex -> assertThat(ex.getFieldErrors())
                        .extracting(FieldError::getField, FieldError::getDefaultMessage)
                        .containsExactly(tuple("items", "항목을 하나 이상 골라 주세요.")));

        assertThatThrownBy(() -> StatExportRequest.parse(
                        StatExportScope.ZONE, 1L, "1h", "2099-01-01", "2099-01-02", null, false))
                .isInstanceOf(BindException.class);

        assertThatThrownBy(() -> StatExportRequest.parse(
                        StatExportScope.ZONE, 1L, "1h", "2099-01-01", "2099-01-02", List.of("COUNT", "PEAK"), false))
                .isInstanceOfSatisfying(BindException.class, ex -> assertThat(ex.getFieldErrors())
                        .extracting(FieldError::getField, FieldError::getDefaultMessage)
                        .containsExactly(tuple("items", "형식이 올바르지 않습니다.")));
    }

    @Test
    @DisplayName("FNC-C-023 단위·시각 형식이 틀리거나 끝이 시작보다 늦지 않으면 칸별 오류를 한 번에 돌려준다")
    void parse_fieldErrors() {
        assertThatThrownBy(() -> StatExportRequest.parse(
                        StatExportScope.ZONE, 1L, "2h", "어제", "2099-01-01", COUNT, false))
                .isInstanceOfSatisfying(BindException.class, ex -> assertThat(ex.getFieldErrors())
                        .extracting(FieldError::getField)
                        .containsExactlyInAnyOrder("interval", "from"));

        assertThatThrownBy(() -> StatExportRequest.parse(
                        StatExportScope.ZONE, 1L, "1h", "2099-01-02", "2099-01-02", COUNT, false))
                .isInstanceOfSatisfying(BindException.class, ex -> assertThat(ex.getFieldErrors())
                        .extracting(FieldError::getField, FieldError::getDefaultMessage)
                        .containsExactly(tuple("to", "끝 시각은 시작 시각보다 뒤여야 합니다.")));
    }

    @Test
    @DisplayName("FNC-C-023 구역·지역은 대상이 있어야 하고, 전체(ALL)는 대상 번호를 쓰지 않는다")
    void parse_target() throws BindException {
        assertThatThrownBy(() -> StatExportRequest.parse(
                        StatExportScope.REGION, null, "1h", "2099-01-01", "2099-01-02", COUNT, false))
                .isInstanceOfSatisfying(BindException.class, ex -> assertThat(ex.getFieldErrors())
                        .extracting(FieldError::getField, FieldError::getDefaultMessage)
                        .containsExactly(tuple("targetId", "대상을 골라 주세요.")));

        StatExportRequest all = StatExportRequest.parse(
                StatExportScope.ALL, 7L, "1h", "2099-01-01", "2099-01-02", COUNT, true);
        assertThat(all.targetId()).isNull();
        assertThat(StatExportRequest.parse(StatExportScope.ALL, null, "1h", "2099-01-01", "2099-01-02", COUNT, false)
                        .scope())
                .isEqualTo(StatExportScope.ALL);
    }

    @Test
    @DisplayName("FNC-C-023 구역별 펼치기는 지역(REGION)일 때만 켜진다")
    void parse_expandOnlyForRegion() throws BindException {
        assertThat(parse(StatExportScope.REGION, "1h", "2099-01-01", "2099-01-02", true).expand()).isTrue();
        assertThat(parse(StatExportScope.REGION, "1h", "2099-01-01", "2099-01-02", false).expand()).isFalse();
        assertThat(parse(StatExportScope.ZONE, "1h", "2099-01-01", "2099-01-02", true).expand()).isFalse();
        assertThat(StatExportRequest.parse(StatExportScope.ALL, null, "1h", "2099-01-01", "2099-01-02", COUNT, true)
                        .expand())
                .isFalse();
    }

    @ParameterizedTest(name = "{0}: {2}까지 되고 {3}이면 RANGE_TOO_LONG")
    @CsvSource({
        "1m, 2099-01-01T00:00, 2099-01-08T00:00, 2099-01-08T00:01, 1분 단위는 최대 7일까지 내려받을 수 있습니다.",
        "1h, 2099-01-01T00:00, 2099-04-04T00:00, 2099-04-04T01:00, 1시간 단위는 최대 93일까지 내려받을 수 있습니다.",
        "1d, 2099-01-01, 2109-01-01, 2109-01-02, 1일 단위는 최대 10년까지 내려받을 수 있습니다.",
        "1w, 2099-01-01, 2109-01-01, 2109-01-02, 1주 단위는 최대 10년까지 내려받을 수 있습니다.",
        "1M, 2099-01-01, 2109-01-01, 2109-01-02, 1월 단위는 최대 10년까지 내려받을 수 있습니다.",
        "1y, 2099-01-01, 2109-01-01, 2109-01-02, 1년 단위는 최대 10년까지 내려받을 수 있습니다."
    })
    @DisplayName("FNC-C-023 처리 순서 1: 단위별 최대 기간(EXPORT_MAX_RANGE)과 같으면 되고 넘으면 RANGE_TOO_LONG")
    void parse_maxRange(String interval, String from, String allowedTo, String tooLongTo, String message)
            throws BindException {
        assertThat(parse(StatExportScope.ZONE, interval, from, allowedTo, false).interval().getCode())
                .isEqualTo(interval);

        assertThatThrownBy(() -> parse(StatExportScope.ZONE, interval, from, tooLongTo, false))
                .isInstanceOf(RangeTooLongException.class)
                .hasMessage(message)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.RANGE_TOO_LONG);
    }

    @Test
    @DisplayName("FNC-C-023 시각 수는 from이 든 구간부터 to 앞까지 센다(1m 7일 10,080, 1h 93일 2,232, 1M 달 중간 포함)")
    void pointCount() throws BindException {
        assertThat(parse(StatExportScope.ZONE, "1m", "2099-01-01", "2099-01-08", false).pointCount())
                .isEqualTo(10_080);
        assertThat(parse(StatExportScope.ZONE, "1h", "2099-01-01", "2099-04-04", false).pointCount())
                .isEqualTo(2_232);
        // 10:30부터 12:00 앞까지 → 10시·11시 2개
        assertThat(parse(StatExportScope.ZONE, "1h", "2099-01-01T10:30", "2099-01-01T12:00", false).pointCount())
                .isEqualTo(2);
        // 1월 15일 ~ 3월 2일 → 1·2·3월 3개
        assertThat(parse(StatExportScope.ZONE, "1M", "2099-01-15", "2099-03-02", false).pointCount())
                .isEqualTo(3);
    }

    @Test
    @DisplayName("FNC-C-023 검수 기준 100만 행 제한: 대상 줄 수 × 시각 수가 1,000,000과 같으면 되고 넘으면 TOO_MANY_ROWS")
    void checkRowLimit() throws BindException {
        // 1분 7일 = 10,080개 시각
        StatExportRequest week = parse(StatExportScope.REGION, "1m", "2099-01-01", "2099-01-08", true);
        assertThat(week.checkRowLimit(99)).isEqualTo(997_920);

        assertThatThrownBy(() -> week.checkRowLimit(100))
                .isInstanceOf(TooManyRowsException.class)
                .hasMessage("한 번에 최대 1,000,000행까지 내려받을 수 있습니다(예상 1,008,000행). 기간을 줄이거나 단위를 키워 주세요.")
                .extracting("errorCode")
                .isEqualTo(ErrorCode.TOO_MANY_ROWS);

        // 1시간 1,000개 시각 × 1,000줄 = 정확히 1,000,000
        StatExportRequest exact = parse(StatExportScope.ALL, "1h", "2099-01-01T00:00", "2099-02-11T16:00", false);
        assertThat(exact.pointCount()).isEqualTo(1_000);
        assertThat(exact.checkRowLimit(1_000)).isEqualTo(1_000_000);
    }

    private static StatExportRequest parse(
            StatExportScope scope, String interval, String from, String to, boolean expand) throws BindException {
        Long targetId = scope == StatExportScope.ALL ? null : 1L;
        return StatExportRequest.parse(scope, targetId, interval, from, to, COUNT, expand);
    }
}
