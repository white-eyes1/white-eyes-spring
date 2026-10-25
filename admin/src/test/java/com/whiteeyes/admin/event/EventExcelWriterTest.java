package com.whiteeyes.admin.event;

import static org.assertj.core.api.Assertions.assertThat;

import com.whiteeyes.admin.event.dto.EventExportRow;
import com.whiteeyes.admin.event.dto.EventListItem;
import com.whiteeyes.admin.event.service.EventExcelWriter;
import com.whiteeyes.common.event.entity.EventStatus;
import com.whiteeyes.common.event.entity.EventType;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 이벤트 엑셀 작성(FNC-A-039 처리 순서 2): 열 순서와 종류별 값. */
class EventExcelWriterTest {

    private static final OffsetDateTime STARTED = OffsetDateTime.parse("2026-10-12T09:00:00+09:00");
    private static final OffsetDateTime ENDED = OffsetDateTime.parse("2026-10-12T09:10:30+09:00");

    @Test
    @DisplayName("FNC-A-039 첫 행은 명세의 열 순서 머리글")
    void header_followsSpecColumns() throws Exception {
        Sheet sheet = write(List.of());

        List<String> headers = new ArrayList<>();
        sheet.getRow(0).forEach(cell -> headers.add(cell.getStringCellValue()));
        assertThat(headers)
                .containsExactly(
                        "발생 시각",
                        "종료 시각",
                        "지속(분)",
                        "종류",
                        "시·도",
                        "시·군·구",
                        "읍·면·동",
                        "구역",
                        "최고 단계",
                        "최고 밀집도(명/㎡)",
                        "최고 증가율(%)",
                        "처리 상태",
                        "조치 항목",
                        "조치 메모",
                        "처리자",
                        "처리 일시");
    }

    @Test
    @DisplayName("FNC-A-039 혼잡 이벤트: 단계 이름과 밀집도가 들어가고 증가율 칸은 빈다")
    void congestionRow() throws Exception {
        EventListItem item = item(EventType.CONGESTION, ENDED, false, 630, 3, "1.6400", 48, EventStatus.CHECKED);

        Row row = write(List.of(row(item))).getRow(1);

        assertThat(row.getCell(0).getLocalDateTimeCellValue()).isEqualTo(LocalDateTime.of(2026, 10, 12, 9, 0));
        assertThat(row.getCell(1).getLocalDateTimeCellValue()).isEqualTo(LocalDateTime.of(2026, 10, 12, 9, 10, 30));
        assertThat(row.getCell(2).getNumericCellValue()).isEqualTo(10.5);
        assertThat(text(row, 3)).isEqualTo("혼잡");
        assertThat(text(row, 4)).isEqualTo("서울특별시");
        assertThat(text(row, 5)).isEqualTo("종로구");
        assertThat(text(row, 6)).isEqualTo("삼청동");
        assertThat(text(row, 7)).isEqualTo("삼청동 1구역");
        assertThat(text(row, 8)).isEqualTo("위험");
        assertThat(row.getCell(9).getNumericCellValue()).isEqualTo(1.64);
        assertThat(row.getCell(10)).isNull();
        assertThat(text(row, 11)).isEqualTo("확인 중");
        assertThat(row.getCell(12)).isNull();
        assertThat(row.getCell(14)).isNull();
        assertThat(row.getCell(15)).isNull();
    }

    @Test
    @DisplayName("FNC-A-039 급증 이벤트: 증가율이 들어가고 단계·밀집도 칸은 빈다")
    void surgeRow() throws Exception {
        EventListItem item = item(EventType.SURGE, ENDED, false, 600, null, "75.5000", 30, EventStatus.UNCHECKED);

        Row row = write(List.of(row(item))).getRow(1);

        assertThat(text(row, 3)).isEqualTo("급증");
        assertThat(row.getCell(8)).isNull();
        assertThat(row.getCell(9)).isNull();
        assertThat(row.getCell(10).getNumericCellValue()).isEqualTo(75.5);
        assertThat(text(row, 11)).isEqualTo("미확인");
    }

    @Test
    @DisplayName("FNC-A-039 캠 이상 이벤트: 최고 값 칸이 모두 빈다")
    void camFaultRow() throws Exception {
        EventListItem item = item(EventType.CAM_FAULT, ENDED, false, 600, null, null, null, EventStatus.UNCHECKED);

        Row row = write(List.of(row(item))).getRow(1);

        assertThat(text(row, 3)).isEqualTo("캠 이상");
        assertThat(row.getCell(8)).isNull();
        assertThat(row.getCell(9)).isNull();
        assertThat(row.getCell(10)).isNull();
    }

    @Test
    @DisplayName("FNC-A-039 진행 중이면 종료 시각 칸에 '진행 중', 지속은 지금까지의 분")
    void ongoingRow() throws Exception {
        EventListItem item = item(EventType.CONGESTION, null, true, 1800, 2, "0.8000", 10, EventStatus.UNCHECKED);

        Row row = write(List.of(row(item))).getRow(1);

        assertThat(text(row, 1)).isEqualTo("진행 중");
        assertThat(row.getCell(2).getNumericCellValue()).isEqualTo(30.0);
    }

    @Test
    @DisplayName("FNC-A-039 처리 정보: 조치 항목 이름·메모·처리자 아이디·처리 일시")
    void handledRow() throws Exception {
        EventListItem item = item(EventType.CONGESTION, ENDED, false, 600, 2, "0.8000", 10, EventStatus.RESOLVED);
        OffsetDateTime handledAt = OffsetDateTime.parse("2026-10-12T09:20:00+09:00");

        Row row = write(List.of(new EventExportRow(item, "서울특별시", "종로구", "삼청동", "STAFF", "인력 2명 배치", "admin01", handledAt))).getRow(1);

        assertThat(text(row, 11)).isEqualTo("조치완료");
        assertThat(text(row, 12)).isEqualTo("인력 배치");
        assertThat(text(row, 13)).isEqualTo("인력 2명 배치");
        assertThat(text(row, 14)).isEqualTo("admin01");
        assertThat(row.getCell(15).getLocalDateTimeCellValue()).isEqualTo(LocalDateTime.of(2026, 10, 12, 9, 20));
    }

    @Test
    @DisplayName("FNC-A-039 삭제된 구역 이름의 '(삭제됨)'은 그대로 남고, 지역 경로가 없으면 지역 칸이 빈다")
    void deletedZoneAndMissingRegion() throws Exception {
        EventListItem item = new EventListItem(
                1L, EventType.CONGESTION, 7L, "(삭제됨) 사직동 1구역", null, null, true, STARTED, ENDED, false, 600, 2,
                new BigDecimal("0.8000"), 10, EventStatus.UNCHECKED);

        Row row = write(List.of(new EventExportRow(item, null, null, null, null, null, null, null))).getRow(1);

        assertThat(text(row, 7)).isEqualTo("(삭제됨) 사직동 1구역");
        assertThat(row.getCell(4)).isNull();
        assertThat(row.getCell(5)).isNull();
        assertThat(row.getCell(6)).isNull();
    }

    private static EventExportRow row(EventListItem item) {
        return new EventExportRow(item, "서울특별시", "종로구", "삼청동", null, null, null, null);
    }

    private static EventListItem item(
            EventType type,
            OffsetDateTime endedAt,
            boolean ongoing,
            long durationSec,
            Integer peakLevel,
            String peakValue,
            Integer peakCount,
            EventStatus status) {
        return new EventListItem(
                1L, type, 7L, "삼청동 1구역", "종로구 · 삼청동", null, false, STARTED, endedAt, ongoing, durationSec,
                peakLevel, peakValue == null ? null : new BigDecimal(peakValue), peakCount, status);
    }

    private static Sheet write(List<EventExportRow> rows) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (EventExcelWriter writer = new EventExcelWriter()) {
            rows.forEach(writer::append);
            writer.writeTo(out);
        }
        Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(out.toByteArray()));
        assertThat(workbook.getNumberOfSheets()).isEqualTo(1);
        assertThat(workbook.getSheetAt(0).getSheetName()).isEqualTo("이벤트");
        return workbook.getSheetAt(0);
    }

    private static String text(Row row, int column) {
        Cell cell = row.getCell(column);
        return cell == null ? null : cell.getStringCellValue();
    }
}
