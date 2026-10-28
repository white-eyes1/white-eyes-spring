package com.whiteeyes.common.stat.service;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.streaming.SXSSFSheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;

import com.whiteeyes.common.stat.dto.StatExportItem;
import com.whiteeyes.common.stat.dto.StatExportPlan;
import com.whiteeyes.common.stat.dto.StatExportSeries;
import com.whiteeyes.common.stat.dto.StatInterval;
import com.whiteeyes.common.stat.dto.StatSeriesPoint;

/**
 * 통계 엑셀 파일을 쓴다(FNC-C-023 처리 순서 2·3·5). SXSSF(스트리밍)라 메모리에는 최근 행 몇 개만 두고 나머지는
 * 임시 파일로 내린다. 항목마다 시트 하나, 행 = 대상 줄 × 시각. 시각이 같은 행끼리 한 묶음이고, 묶음마다 행
 * 배경색을 #d1d1d1, #ffffff로 번갈아 칠한다(FNC-C-023 처리 순서 2).
 *
 * <p>열: 시각, 시·도, 시·군·구, 읍·면·동, 구역, 항목 값, 시연용. 인원은 평균·최대·최소 3열.
 * 통계가 없는 시각은 값 칸을 비운다(색을 칠하려고 빈 칸도 칸은 둔다). 다 쓰면 {@link #close()}로 임시 파일을 지운다.
 */
public class StatExcelWriter implements AutoCloseable {

    // 메모리에 둘 행 수(SXSSF 기본값)
    private static final int WINDOW_SIZE = 100;
    private static final String[] NAME_HEADERS = {"시각", "시·도", "시·군·구", "읍·면·동", "구역"};
    private static final String DEMO_HEADER = "시연용";
    // 시각 묶음의 배경색: 첫 묶음부터 번갈아
    private static final byte[][] GROUP_COLORS = {
        {(byte) 0xD1, (byte) 0xD1, (byte) 0xD1}, {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF}
    };
    private static final DateTimeFormatter FILE_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    // 파일 이름에 쓸 수 없는 글자
    private static final String UNSAFE_FILE_CHARS = "[\\\\/:*?\"<>|]";

    private static final XSSFColor BORDER_COLOR =
            new XSSFColor(new byte[] {(byte) 0xBF, (byte) 0xBF, (byte) 0xBF}, null);

    private final SXSSFWorkbook workbook = new SXSSFWorkbook(WINDOW_SIZE);
    private final Map<StatExportItem, SXSSFSheet> sheets = new EnumMap<>(StatExportItem.class);
    private final Map<StatExportItem, Integer> nextRows = new EnumMap<>(StatExportItem.class);
    private final String timeFormat;
    // 칸 종류(시각·글자·소수 자릿수) × 묶음 색 순서 → 서식
    private final Map<StyleKey, CellStyle> styles = new HashMap<>();
    private LocalDateTime lastAt;
    private int groupIndex = -1;

    public StatExcelWriter(StatExportPlan plan) {
        workbook.setCompressTempFiles(true);
        timeFormat = timeFormat(plan.request().interval());
        CellStyle headerStyle = workbook.createCellStyle();
        Font bold = workbook.createFont();
        bold.setBold(true);
        headerStyle.setFont(bold);

        for (StatExportItem item : plan.request().items()) {
            SXSSFSheet sheet = workbook.createSheet(item.getLabel());
            Row header = sheet.createRow(0);
            int column = 0;
            for (String name : NAME_HEADERS) {
                header.createCell(column++).setCellValue(name);
            }
            for (Column value : columns(item)) {
                header.createCell(column++).setCellValue(value.header());
            }
            header.createCell(column).setCellValue(DEMO_HEADER);
            header.forEach(cell -> cell.setCellStyle(headerStyle));
            sheet.createFreezePane(0, 1);
            setWidths(sheet, column + 1);
            sheets.put(item, sheet);
            nextRows.put(item, 1);
        }
    }

    /**
     * 대상 줄의 한 시각을 시트마다 한 행씩 쓴다. 통계 엑셀 값 읽기(StatExportReader.read)가 넘기는 순서대로 부른다.
     * 앞 행과 시각이 다르면 새 시각 묶음이라 배경색을 바꾼다.
     */
    public void append(StatExportSeries series, StatSeriesPoint point) {
        LocalDateTime at = point.at().toLocalDateTime();
        if (!at.equals(lastAt)) {
            lastAt = at;
            groupIndex++;
        }
        int color = groupIndex % GROUP_COLORS.length;
        boolean empty = point.sampleSec() == 0;
        sheets.forEach((item, sheet) -> {
            int rowIndex = nextRows.merge(item, 1, Integer::sum) - 1;
            Row row = sheet.createRow(rowIndex);
            row.createCell(0).setCellValue(at);
            row.getCell(0).setCellStyle(style(StyleKey.time(color)));
            setText(row, 1, series.sido(), color);
            setText(row, 2, series.sigungu(), color);
            setText(row, 3, series.emd(), color);
            setText(row, 4, series.zoneName(), color);
            int column = NAME_HEADERS.length;
            for (Column value : columns(item)) {
                BigDecimal number = empty ? null : value.value().apply(point);
                Cell cell = row.createCell(column);
                if (number != null) {
                    cell.setCellValue(number.doubleValue());
                }
                cell.setCellStyle(style(StyleKey.decimal(value.decimals(), color)));
                column++;
            }
            // 1분 통계에는 시연용 데이터가 없어 항상 '아니오'(tables.md zone_stat_minute)
            row.createCell(column).setCellValue(point.isDemo() ? "예" : "아니오");
            row.getCell(column).setCellStyle(style(StyleKey.text(color)));
        });
    }

    /** 다 쓴 파일을 내보낸다. */
    public void writeTo(OutputStream out) throws IOException {
        workbook.write(out);
    }

    /** 임시 파일을 지운다. */
    @Override
    public void close() throws IOException {
        workbook.close();
    }

    /**
     * 파일 이름: stats_{대상이름}_{from}_{to}.xlsx(FNC-C-023 처리 순서 5). 날짜는 yyyyMMdd, to는 마지막으로 들어간 날
     * (to 미만이라 to에서 1분 뺀 날). 파일 이름에 쓸 수 없는 글자는 '_'로 바꾼다.
     */
    public static String fileName(StatExportPlan plan) {
        String target = plan.targetName().replaceAll(UNSAFE_FILE_CHARS, "_");
        String from = plan.request().from().format(FILE_DATE);
        String to = plan.request().to().minusMinutes(1).format(FILE_DATE);
        return "stats_" + target + "_" + from + "_" + to + ".xlsx";
    }

    // 1m·1h는 분까지, 1d·1w는 날짜, 1M은 연-월, 1y는 연
    private static String timeFormat(StatInterval interval) {
        return switch (interval) {
            case MINUTE, HOUR -> "yyyy-mm-dd hh:mm";
            case DAY, WEEK -> "yyyy-mm-dd";
            case MONTH -> "yyyy-mm";
            case YEAR -> "yyyy";
        };
    }

    // 인원은 평균·최대·최소, 유입·유출은 방향(N·E·S·W) 합(통계 그래프와 같음)
    private static List<Column> columns(StatExportItem item) {
        return switch (item) {
            case COUNT -> List.of(
                    new Column("평균 인원(명)", 2, StatSeriesPoint::countAvg),
                    new Column("최대 인원(명)", 0, point -> toDecimal(point.countMax())),
                    new Column("최소 인원(명)", 0, point -> toDecimal(point.countMin())));
            case DENSITY -> List.of(new Column("밀집도(명/㎡)", 4, StatSeriesPoint::density));
            case INFLOW -> List.of(new Column("유입(명)", 0, point -> sum(point.inflow())));
            case OUTFLOW -> List.of(new Column("유출(명)", 0, point -> sum(point.outflow())));
            case DWELL -> List.of(new Column("평균 체류(초)", 1, StatSeriesPoint::dwellAvgSec));
        };
    }

    private static BigDecimal toDecimal(Integer value) {
        return value == null ? null : BigDecimal.valueOf(value);
    }

    private static BigDecimal sum(Map<String, Integer> flow) {
        return flow == null ? null : BigDecimal.valueOf(flow.values().stream().mapToInt(Integer::intValue).sum());
    }

    // 같은 서식은 한 번만 만든다(엑셀은 서식 수에 제한이 있다)
    private CellStyle style(StyleKey key) {
        return styles.computeIfAbsent(key, ignored -> {
            XSSFCellStyle style = (XSSFCellStyle) workbook.createCellStyle();
            if (key.isTime()) {
                style.setDataFormat(workbook.createDataFormat().getFormat(timeFormat));
            } else if (key.decimals() >= 0) {
                style.setDataFormat(workbook.createDataFormat()
                        .getFormat(key.decimals() == 0 ? "#,##0" : "#,##0." + "0".repeat(key.decimals())));
            }
            style.setFillForegroundColor(new XSSFColor(GROUP_COLORS[key.color()], null));
            style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            // 배경색을 칠하면 엑셀 기본 눈금선이 가려지므로 칸마다 가는 테두리로 눈금선 모양을 지킨다
            style.setBorderTop(BorderStyle.THIN);
            style.setBorderBottom(BorderStyle.THIN);
            style.setBorderLeft(BorderStyle.THIN);
            style.setBorderRight(BorderStyle.THIN);
            style.setTopBorderColor(BORDER_COLOR);
            style.setBottomBorderColor(BORDER_COLOR);
            style.setLeftBorderColor(BORDER_COLOR);
            style.setRightBorderColor(BORDER_COLOR);
            return style;
        });
    }

    // 이름이 없는 칸도 색을 칠하려고 빈 칸으로 둔다
    private void setText(Row row, int column, String text, int color) {
        Cell cell = row.createCell(column);
        if (text != null) {
            cell.setCellValue(text);
        }
        cell.setCellStyle(style(StyleKey.text(color)));
    }

    // 스트리밍 시트는 자동 너비를 쓸 수 없어 고정 너비(글자 수 × 256)
    private static void setWidths(SXSSFSheet sheet, int columnCount) {
        sheet.setColumnWidth(0, 18 * 256);
        for (int column = 1; column < columnCount; column++) {
            sheet.setColumnWidth(column, 14 * 256);
        }
    }

    /**
     * 칸 서식의 종류. decimals는 값 칸의 소수 자릿수, 시각 칸은 TIME(-2), 글자 칸은 TEXT(-1). color는 묶음 색 순서.
     */
    private record StyleKey(int decimals, int color) {

        private static final int TIME = -2;
        private static final int TEXT = -1;

        static StyleKey time(int color) {
            return new StyleKey(TIME, color);
        }

        static StyleKey text(int color) {
            return new StyleKey(TEXT, color);
        }

        static StyleKey decimal(int decimals, int color) {
            return new StyleKey(decimals, color);
        }

        boolean isTime() {
            return decimals == TIME;
        }
    }

    /** 항목 값 열 하나. */
    private record Column(String header, int decimals, Function<StatSeriesPoint, BigDecimal> value) {
    }
}
