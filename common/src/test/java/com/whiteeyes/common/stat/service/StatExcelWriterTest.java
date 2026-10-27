package com.whiteeyes.common.stat.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.validation.BindException;

import com.whiteeyes.common.stat.dto.StatExportPlan;
import com.whiteeyes.common.stat.dto.StatExportRequest;
import com.whiteeyes.common.stat.dto.StatExportScope;
import com.whiteeyes.common.stat.dto.StatExportSeries;
import com.whiteeyes.common.stat.dto.StatSeriesPoint;

/** 통계 엑셀 다운로드(FNC-C-023) 파일 쓰기 */
class StatExcelWriterTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2099, 1, 1, 10, 0);
    private static final StatExportSeries SAJIK =
            new StatExportSeries(1L, "서울특별시", "종로구", "사직동", "사직동 1구역");
    private static final StatExportSeries JONGNO =
            new StatExportSeries(null, "서울특별시", "종로구", "(지역 합산)", "(지역 합산)");

    @Test
    @DisplayName("FNC-C-023 처리 순서 2·3: 항목마다 시트 하나(고른 순서), 열은 시각·시·구·동·구역·항목 값·시연용")
    void sheetsAndHeaders() throws Exception {
        StatExportPlan plan = plan("1h", List.of("DWELL", "COUNT", "DENSITY", "INFLOW", "OUTFLOW"));

        try (XSSFWorkbook workbook = write(plan, List.of())) {
            assertThat(sheetNames(workbook)).containsExactly("평균 체류", "인원", "밀집도", "유입", "유출");
            assertThat(texts(workbook.getSheet("인원").getRow(0))).containsExactly(
                    "시각", "시·도", "시·군·구", "읍·면·동", "구역", "평균 인원(명)", "최대 인원(명)", "최소 인원(명)", "시연용");
            assertThat(texts(workbook.getSheet("밀집도").getRow(0)))
                    .endsWith("구역", "밀집도(명/㎡)", "시연용");
            assertThat(texts(workbook.getSheet("평균 체류").getRow(0))).endsWith("평균 체류(초)", "시연용");
            // 머리글은 스크롤해도 보이게 고정
            assertThat(workbook.getSheet("인원").getPaneInformation().getHorizontalSplitPosition()).isEqualTo((short) 1);
        }
    }

    @Test
    @DisplayName("FNC-C-023 처리 순서 3: 행마다 시각(날짜 칸)·이름·값을 쓰고, 지역 합산은 아래 단계 칸에 '(지역 합산)'")
    void rows() throws Exception {
        StatExportPlan plan = plan("1h", List.of("COUNT", "DENSITY", "INFLOW", "DWELL"));
        List<Line> lines = List.of(
                new Line(SAJIK, point(T0, "120.50", 150, 90, "0.9213", Map.of("N", 3, "E", 4), "312.5", false)),
                new Line(JONGNO, point(T0.plusHours(1), "80.00", 100, 60, "0.4000", Map.of("S", 7), null, true)));

        try (XSSFWorkbook workbook = write(plan, lines)) {
            Row count = workbook.getSheet("인원").getRow(1);
            assertThat(count.getCell(0).getLocalDateTimeCellValue()).isEqualTo(T0);
            assertThat(count.getCell(0).getCellStyle().getDataFormatString()).isEqualTo("yyyy-mm-dd hh:mm");
            assertThat(texts(count).subList(1, 5)).containsExactly("서울특별시", "종로구", "사직동", "사직동 1구역");
            assertThat(numbers(count, 5, 8)).containsExactly(120.5, 150.0, 90.0);
            assertThat(count.getCell(8).getStringCellValue()).isEqualTo("아니오");

            Row regionRow = workbook.getSheet("인원").getRow(2);
            assertThat(texts(regionRow).subList(1, 5)).containsExactly("서울특별시", "종로구", "(지역 합산)", "(지역 합산)");
            assertThat(regionRow.getCell(8).getStringCellValue()).isEqualTo("예");

            Row density = workbook.getSheet("밀집도").getRow(1);
            assertThat(density.getCell(5).getNumericCellValue()).isEqualTo(0.9213);
            assertThat(density.getCell(5).getCellStyle().getDataFormatString()).isEqualTo("#,##0.0000");
            // 유입은 방향 합
            assertThat(workbook.getSheet("유입").getRow(1).getCell(5).getNumericCellValue()).isEqualTo(7.0);
            assertThat(workbook.getSheet("평균 체류").getRow(1).getCell(5).getNumericCellValue()).isEqualTo(312.5);
            // 체류를 잰 사람이 없으면 빈 칸
            assertThat(workbook.getSheet("평균 체류").getRow(2).getCell(5)).isNull();
        }
    }

    @Test
    @DisplayName("FNC-C-023 통계가 없는 시각은 시각·이름만 있고 값 칸은 빈다, 시연용은 '아니오'")
    void emptyPoint() throws Exception {
        StatExportPlan plan = plan("1h", List.of("COUNT"));
        StatSeriesPoint empty = StatSeriesPoint.empty(T0.atOffset(ZoneOffset.ofHours(9)));

        try (XSSFWorkbook workbook = write(plan, List.of(new Line(SAJIK, empty)))) {
            Row row = workbook.getSheet("인원").getRow(1);
            assertThat(row.getCell(0).getLocalDateTimeCellValue()).isEqualTo(T0);
            assertThat(row.getCell(4).getStringCellValue()).isEqualTo("사직동 1구역");
            assertThat(row.getCell(5)).isNull();
            assertThat(row.getCell(6)).isNull();
            assertThat(row.getCell(7)).isNull();
            assertThat(row.getCell(8).getStringCellValue()).isEqualTo("아니오");
        }
    }

    @Test
    @DisplayName("FNC-C-023 시각 칸 표시는 단위에 맞춘다(1d 날짜, 1M 연-월, 1y 연)")
    void timeFormatByInterval() throws Exception {
        Map<String, String> formats = Map.of("1d", "yyyy-mm-dd", "1M", "yyyy-mm", "1y", "yyyy");
        for (Map.Entry<String, String> entry : formats.entrySet()) {
            StatExportPlan plan = planOf(entry.getKey(), "2099-01-01", "2100-01-01", List.of("COUNT"));
            LocalDateTime at = LocalDateTime.of(2099, 1, 1, 0, 0);
            Line line = new Line(SAJIK, point(at, "1.00", 1, 1, "0.1000", Map.of(), null, false));
            try (XSSFWorkbook workbook = write(plan, List.of(line))) {
                assertThat(workbook.getSheet("인원").getRow(1).getCell(0).getCellStyle().getDataFormatString())
                        .isEqualTo(entry.getValue());
            }
        }
    }

    @Test
    @DisplayName("FNC-C-023 스트리밍으로 써도 행이 모두 남는다(시트 2개 × 5,000행)")
    void manyRows() throws Exception {
        StatExportPlan plan =
                planOf("1m", "2099-01-01T00:00", "2099-01-04T11:20", List.of("COUNT", "DENSITY"));
        List<Line> lines = new ArrayList<>();
        for (int i = 0; i < 5_000; i++) {
            StatSeriesPoint point = point(T0.plusMinutes(i), String.valueOf(i), i, i, "0.0001", Map.of(), null, false);
            lines.add(new Line(SAJIK, point));
        }

        try (XSSFWorkbook workbook = write(plan, lines)) {
            for (Sheet sheet : workbook) {
                assertThat(sheet.getLastRowNum()).isEqualTo(5_000);
            }
            Row last = workbook.getSheet("인원").getRow(5_000);
            assertThat(last.getCell(0).getLocalDateTimeCellValue()).isEqualTo(T0.plusMinutes(4_999));
            assertThat(last.getCell(5).getNumericCellValue()).isEqualTo(4_999.0);
        }
    }

    @Test
    @DisplayName("FNC-C-023 처리 순서 5: 파일 이름 stats_{대상이름}_{from}_{to}.xlsx, to는 마지막으로 들어간 날, 전체는 '전체'")
    void fileName() throws BindException {
        StatExportRequest week = StatExportRequest.parse(
                StatExportScope.ALL, null, "1h", "2026-10-15", "2026-10-22", List.of("COUNT"), false);
        assertThat(StatExcelWriter.fileName(new StatExportPlan(week, "전체", List.of(), 0, 0)))
                .isEqualTo("stats_전체_20261015_20261021.xlsx");

        StatExportRequest day = StatExportRequest.parse(
                StatExportScope.ZONE, 1L, "1h", "2026-10-21T09:00", "2026-10-21T18:30", List.of("COUNT"), false);
        assertThat(StatExcelWriter.fileName(new StatExportPlan(day, "A/B:구역*1", List.of(), 1, 0)))
                .isEqualTo("stats_A_B_구역_1_20261021_20261021.xlsx");
    }

    private static XSSFWorkbook write(StatExportPlan plan, List<Line> lines) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (StatExcelWriter writer = new StatExcelWriter(plan)) {
            lines.forEach(line -> writer.append(line.series(), line.point()));
            writer.writeTo(out);
        }
        return new XSSFWorkbook(new ByteArrayInputStream(out.toByteArray()));
    }

    private static StatExportPlan plan(String interval, List<String> items) throws BindException {
        return planOf(interval, "2099-01-01T10:00", "2099-01-01T12:00", items);
    }

    // 시험용 계획(대상 확인 없이 조건만)
    private static StatExportPlan planOf(String interval, String from, String to, List<String> items)
            throws BindException {
        StatExportRequest request = StatExportRequest.parse(StatExportScope.ZONE, 1L, interval, from, to, items, false);
        return new StatExportPlan(request, "사직동 1구역", List.of(SAJIK), 1, 0);
    }

    private static StatSeriesPoint point(
            LocalDateTime at, String countAvg, int countMax, int countMin, String density, Map<String, Integer> inflow,
            String dwellAvgSec, boolean demo) {
        return new StatSeriesPoint(
                at.atOffset(ZoneOffset.ofHours(9)),
                new BigDecimal(countAvg),
                countMax,
                countMin,
                new BigDecimal(density),
                1,
                inflow,
                Map.of("N", 1),
                dwellAvgSec == null ? null : new BigDecimal(dwellAvgSec),
                3600,
                demo);
    }

    private static List<String> sheetNames(XSSFWorkbook workbook) {
        List<String> names = new ArrayList<>();
        workbook.forEach(sheet -> names.add(sheet.getSheetName()));
        return names;
    }

    private static List<String> texts(Row row) {
        List<String> texts = new ArrayList<>();
        for (int column = 0; column < row.getLastCellNum(); column++) {
            Cell cell = row.getCell(column);
            texts.add(cell == null || cell.getCellType() != CellType.STRING
                    ? null
                    : cell.getStringCellValue());
        }
        return texts;
    }

    private static List<Double> numbers(Row row, int from, int to) {
        List<Double> numbers = new ArrayList<>();
        for (int column = from; column < to; column++) {
            numbers.add(row.getCell(column).getNumericCellValue());
        }
        return numbers;
    }

    private record Line(StatExportSeries series, StatSeriesPoint point) {
    }

}
