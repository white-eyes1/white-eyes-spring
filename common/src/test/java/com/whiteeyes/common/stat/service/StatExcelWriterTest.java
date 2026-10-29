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

import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
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
    @DisplayName("FNC-C-023 처리 순서 2·3: 시트 하나(통계), 열은 시각·시·구·동·구역·고른 항목(요청 순서와 상관없이 고정 순서)")
    void sheetsAndHeaders() throws Exception {
        StatExportPlan plan = plan("1h", List.of("DWELL", "COUNT", "DENSITY", "INFLOW", "OUTFLOW"));

        try (XSSFWorkbook workbook = write(plan, List.of())) {
            assertThat(sheetNames(workbook)).containsExactly("통계");
            // 요청은 DWELL부터지만 열은 인원 → 밀집도 → 유입 → 유출 → 평균 체류 순
            assertThat(texts(workbook.getSheet("통계").getRow(0))).containsExactly(
                    "시각", "시·도", "시·군·구", "읍·면·동", "구역", "평균 인원(명)", "최대 인원(명)", "최소 인원(명)",
                    "밀집도(명/㎡)", "유입(명)", "유출(명)", "평균 체류(초)");
            // 머리글은 스크롤해도 보이게 고정
            assertThat(workbook.getSheet("통계").getPaneInformation().getHorizontalSplitPosition()).isEqualTo((short) 1);
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
            Sheet sheet = workbook.getSheet("통계");
            Row count = sheet.getRow(1);
            assertThat(count.getCell(0).getLocalDateTimeCellValue()).isEqualTo(T0);
            assertThat(count.getCell(0).getCellStyle().getDataFormatString()).isEqualTo("yyyy-mm-dd hh:mm");
            assertThat(texts(count).subList(1, 5)).containsExactly("서울특별시", "종로구", "사직동", "사직동 1구역");
            assertThat(numbers(count, 5, 8)).containsExactly(120.5, 150.0, 90.0);
            // 한 행에 고른 항목이 나란히: 이름 5칸 + 인원 3칸 + 밀집도 + 유입 + 평균 체류
            assertThat(count.getLastCellNum()).isEqualTo((short) 11);
            assertThat(count.getCell(8).getNumericCellValue()).isEqualTo(0.9213);
            assertThat(count.getCell(8).getCellStyle().getDataFormatString()).isEqualTo("#,##0.0000");
            // 유입은 방향 합
            assertThat(count.getCell(9).getNumericCellValue()).isEqualTo(7.0);
            assertThat(count.getCell(10).getNumericCellValue()).isEqualTo(312.5);

            Row regionRow = sheet.getRow(2);
            assertThat(texts(regionRow).subList(1, 5)).containsExactly("서울특별시", "종로구", "(지역 합산)", "(지역 합산)");
            // 시연용 행(demo=true)이어도 열은 늘지 않는다
            assertThat(regionRow.getLastCellNum()).isEqualTo((short) 11);
            // 항목마다 따로 비운다: 체류를 잰 사람이 없으면 평균 체류만 빈 칸이고 나머지 값은 그대로
            assertThat(regionRow.getCell(10).getCellType()).isEqualTo(CellType.BLANK);
            assertThat(regionRow.getCell(9).getNumericCellValue()).isEqualTo(7.0);
            assertThat(regionRow.getCell(8).getNumericCellValue()).isEqualTo(0.4);
        }
    }

    @Test
    @DisplayName("FNC-C-023 처리 순서 2: 시각 묶음마다 행 배경색을 #d1d1d1, #ffffff로 번갈아 칠한다(행마다가 아님, 모든 칸)")
    void rowColorAlternatesPerTimeGroup() throws Exception {
        StatExportPlan plan = plan("1h", List.of("COUNT", "DENSITY"));
        StatExportSeries other = new StatExportSeries(2L, "서울특별시", "종로구", "삼청동", "삼청동 1구역");
        List<Line> lines = new ArrayList<>();
        for (int hour = 0; hour < 3; hour++) {
            lines.add(new Line(SAJIK, point(T0.plusHours(hour), "1.00", 1, 1, "0.1000", Map.of(), null, false)));
            lines.add(new Line(other, StatSeriesPoint.empty(T0.plusHours(hour).atOffset(ZoneOffset.ofHours(9)))));
        }

        try (XSSFWorkbook workbook = write(plan, lines)) {
            Sheet sheet = workbook.getSheet("통계");
            // 1~2행 10시, 3~4행 11시, 5~6행 12시. 항목 칸(인원 3칸·밀집도)까지 같은 색
            String[] expected = {"D1D1D1", "D1D1D1", "FFFFFF", "FFFFFF", "D1D1D1", "D1D1D1"};
            for (int rowIndex = 1; rowIndex <= 6; rowIndex++) {
                Row row = sheet.getRow(rowIndex);
                assertThat(row.getLastCellNum()).isEqualTo((short) 9);
                for (int column = 0; column < row.getLastCellNum(); column++) {
                    assertThat(fillRgb(row.getCell(column)))
                            .as("%d행 %d열", rowIndex, column)
                            .isEqualTo(expected[rowIndex - 1]);
                }
            }
            // 머리글 행에는 칠하지 않는다
            assertThat(sheet.getRow(0).getCell(0).getCellStyle().getFillPattern()).isEqualTo(FillPatternType.NO_FILL);
        }
    }

    @Test
    @DisplayName("FNC-C-023 구역이 하나뿐이어도(구역·지역 합산) 시각마다 배경색을 번갈아 칠한다")
    void rowColorAlternatesWithSingleSeries() throws Exception {
        StatExportPlan plan = plan("1h", List.of("COUNT"));
        List<Line> lines = List.of(
                new Line(JONGNO, point(T0, "1.00", 1, 1, "0.1000", Map.of(), null, false)),
                new Line(JONGNO, point(T0.plusHours(1), "1.00", 1, 1, "0.1000", Map.of(), null, false)),
                new Line(JONGNO, point(T0.plusHours(2), "1.00", 1, 1, "0.1000", Map.of(), null, false)));

        try (XSSFWorkbook workbook = write(plan, lines)) {
            Sheet sheet = workbook.getSheet("통계");
            assertThat(fillRgb(sheet.getRow(1).getCell(0))).isEqualTo("D1D1D1");
            assertThat(fillRgb(sheet.getRow(2).getCell(0))).isEqualTo("FFFFFF");
            assertThat(fillRgb(sheet.getRow(3).getCell(7))).isEqualTo("D1D1D1");
        }
    }

    @Test
    @DisplayName("FNC-C-023 통계가 없는 시각은 시각·이름만 있고 값 칸은 항목마다 빈다")
    void emptyPoint() throws Exception {
        StatExportPlan plan = plan("1h", List.of("COUNT", "DENSITY"));
        StatSeriesPoint empty = StatSeriesPoint.empty(T0.atOffset(ZoneOffset.ofHours(9)));

        try (XSSFWorkbook workbook = write(plan, List.of(new Line(SAJIK, empty)))) {
            Row row = workbook.getSheet("통계").getRow(1);
            assertThat(row.getCell(0).getLocalDateTimeCellValue()).isEqualTo(T0);
            assertThat(row.getCell(4).getStringCellValue()).isEqualTo("사직동 1구역");
            // 배경색을 칠하려고 빈 칸도 칸은 있고 값만 비어 있다
            assertThat(row.getCell(5).getCellType()).isEqualTo(CellType.BLANK);
            assertThat(row.getCell(6).getCellType()).isEqualTo(CellType.BLANK);
            assertThat(row.getCell(7).getCellType()).isEqualTo(CellType.BLANK);
            assertThat(row.getCell(8).getCellType()).isEqualTo(CellType.BLANK);
            assertThat(row.getLastCellNum()).isEqualTo((short) 9);
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
                assertThat(workbook.getSheet("통계").getRow(1).getCell(0).getCellStyle().getDataFormatString())
                        .isEqualTo(entry.getValue());
            }
        }
    }

    @Test
    @DisplayName("FNC-C-023 스트리밍으로 써도 행이 모두 남는다(한 시트 5,000행)")
    void manyRows() throws Exception {
        StatExportPlan plan =
                planOf("1m", "2099-01-01T00:00", "2099-01-04T11:20", List.of("COUNT", "DENSITY"));
        List<Line> lines = new ArrayList<>();
        for (int i = 0; i < 5_000; i++) {
            StatSeriesPoint point = point(T0.plusMinutes(i), String.valueOf(i), i, i, "0.0001", Map.of(), null, false);
            lines.add(new Line(SAJIK, point));
        }

        try (XSSFWorkbook workbook = write(plan, lines)) {
            assertThat(workbook.getNumberOfSheets()).isEqualTo(1);
            Sheet sheet = workbook.getSheet("통계");
            assertThat(sheet.getLastRowNum()).isEqualTo(5_000);
            Row last = sheet.getRow(5_000);
            assertThat(last.getCell(0).getLocalDateTimeCellValue()).isEqualTo(T0.plusMinutes(4_999));
            assertThat(last.getCell(5).getNumericCellValue()).isEqualTo(4_999.0);
            assertThat(last.getCell(8).getNumericCellValue()).isEqualTo(0.0001);
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

    // 칸 배경색(RGB 16진수 대문자)
    private static String fillRgb(Cell cell) {
        XSSFCellStyle style = (XSSFCellStyle) cell.getCellStyle();
        assertThat(style.getFillPattern()).isEqualTo(FillPatternType.SOLID_FOREGROUND);
        // 배경색을 칠하면 엑셀 기본 눈금선이 가려지므로 칸마다 가는 테두리를 둔다
        assertThat(List.of(style.getBorderTop(), style.getBorderBottom(), style.getBorderLeft(), style.getBorderRight()))
                .containsOnly(BorderStyle.THIN);
        assertThat(style.getTopBorderXSSFColor().getARGBHex().substring(2)).isEqualTo("BFBFBF");
        return style.getFillForegroundXSSFColor().getARGBHex().substring(2);
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
