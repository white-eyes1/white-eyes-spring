package com.whiteeyes.common.stat.service;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
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
 * 임시 파일로 내린다. 시트는 '통계' 하나, 행 = 대상 줄 × 시각. 시각이 같은 행끼리 한 묶음이고, 묶음마다 행
 * 배경색을 #d1d1d1, #ffffff로 번갈아 칠한다(FNC-C-023 처리 순서 2).
 *
 * <p>열: 시각, 시·도, 시·군·구, 읍·면·동, 구역, 고른 항목 값. 항목 열은 요청 순서와 상관없이 인원(평균·최대·최소 3열),
 * 밀집도, 유입, 유출, 평균 체류 순이다(FNC-C-023 처리 순서 3). 통계가 없는 시각은 값 칸을 항목마다 따로 비운다(색을
 * 칠하려고 빈 칸도 칸은 둔다). 다 쓰면 {@link #close()}로 임시 파일을 지운다.
 */
public class StatExcelWriter implements AutoCloseable {

    // 메모리에 둘 행 수(SXSSF 기본값)
    // SXSSF는 이 개수를 넘는 오래된 행을 임시 파일로 내려 메모리를 아낀다. 그래서 이미 쓴 행은 다시 고칠 수 없다
    private static final int WINDOW_SIZE = 100;
    // 시트 이름. 엑셀 파일에는 시트가 이것 하나뿐이다
    private static final String SHEET_NAME = "통계";
    // 항목 값 열 앞에 항상 오는 다섯 열의 머리글
    private static final String[] NAME_HEADERS = {"시각", "시·도", "시·군·구", "읍·면·동", "구역"};
    // 시각 묶음의 배경색: 첫 묶음부터 번갈아
    // RGB 바이트 값. 같은 시각 묶음은 같은 색이고, 다음 시각 묶음에서 다른 색으로 바뀌어 묶음 경계가 눈에 띈다
    private static final byte[][] GROUP_COLORS = {
        {(byte) 0xD1, (byte) 0xD1, (byte) 0xD1}, {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF}
    };
    // 파일 이름에 넣는 날짜 형식
    private static final DateTimeFormatter FILE_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    // 파일 이름에 쓸 수 없는 글자
    // 정규식 문자 집합. 윈도우·브라우저 다운로드에서 파일 이름에 쓸 수 없는 글자를 가리킨다
    private static final String UNSAFE_FILE_CHARS = "[\\\\/:*?\"<>|]";

    // 칸 테두리 색(연한 회색)
    private static final XSSFColor BORDER_COLOR =
            new XSSFColor(new byte[] {(byte) 0xBF, (byte) 0xBF, (byte) 0xBF}, null);

    // 스트리밍 방식 엑셀 통합 문서. 행 수가 많아도 메모리에 일부만 둔다
    private final SXSSFWorkbook workbook = new SXSSFWorkbook(WINDOW_SIZE);
    private final SXSSFSheet sheet;
    // 고른 항목에 해당하는 값 열 목록(항목 하나가 여러 열이 될 수 있다. 예: 인원은 평균·최대·최소 3열)
    private final List<Column> valueColumns;
    // 시각 칸의 엑셀 표시 형식. 조회 단위에 따라 달라진다(분 단위는 시:분, 연 단위는 연도만)
    private final String timeFormat;
    // 칸 종류(시각·글자·소수 자릿수) × 묶음 색 순서 → 서식
    private final Map<StyleKey, CellStyle> styles = new HashMap<>();
    // 바로 앞 행의 시각. 시각이 바뀌는 지점에서 묶음 색을 바꾸려고 기억해 둔다
    private LocalDateTime lastAt;
    // 몇 번째 시각 묶음인지. 첫 행에서 시각이 달라지며 0이 되도록 -1에서 시작한다
    private int groupIndex = -1;
    // 다음에 쓸 행 번호. 0번 행은 머리글이라 1부터 시작한다
    private int nextRow = 1;

    /** 계획에 맞춰 시트와 머리글을 만든다. 이후 {@link #append}로 행을 추가한다 */
    public StatExcelWriter(StatExportPlan plan) {
        // 임시 파일을 압축해 디스크 사용량을 줄인다
        workbook.setCompressTempFiles(true);
        timeFormat = timeFormat(plan.request().interval());
        // 머리글만 굵게 보이도록 서식을 만든다
        CellStyle headerStyle = workbook.createCellStyle();
        Font bold = workbook.createFont();
        bold.setBold(true);
        headerStyle.setFont(bold);

        // 항목 열은 고른 순서가 아니라 StatExportItem 선언 순서(인원 → 밀집도 → 유입 → 유출 → 평균 체류)
        // 열 순서를 요청 순서가 아니라 enum 선언 순서로 고정한다(요청마다 열 순서가 달라지지 않게)
        valueColumns = Arrays.stream(StatExportItem.values())
                .filter(plan.request().items()::contains)
                .flatMap(item -> columns(item).stream())
                .toList();

        sheet = workbook.createSheet(SHEET_NAME);
        Row header = sheet.createRow(0);
        int column = 0;
        for (String name : NAME_HEADERS) {
            header.createCell(column++).setCellValue(name);
        }
        for (Column value : valueColumns) {
            header.createCell(column++).setCellValue(value.header());
        }
        header.forEach(cell -> cell.setCellStyle(headerStyle));
        // 머리글 행을 고정해 아래로 스크롤해도 보이게 한다
        sheet.createFreezePane(0, 1);
        // 스트리밍 시트는 자동 너비를 쓸 수 없어 여기서 열 너비를 직접 정한다
        setWidths(sheet, column);
    }

    /**
     * 대상 줄의 한 시각을 한 행으로 쓴다(고른 항목 값이 나란히). 통계 엑셀 값 읽기(StatExportReader.read)가 넘기는 순서대로 부른다.
     * 앞 행과 시각이 다르면 새 시각 묶음이라 배경색을 바꾼다.
     */
    public void append(StatExportSeries series, StatSeriesPoint point) {
        // 점의 시각(오프셋 제외 한국 시각). 시각 칸과 묶음 판정에 쓴다
        LocalDateTime at = point.at().toLocalDateTime();
        // 시각이 바뀌면 새 묶음이다(같은 시각의 여러 구역 행은 연속해서 들어온다는 전제)
        if (!at.equals(lastAt)) {
            lastAt = at;
            groupIndex++;
        }
        // 묶음 번호의 홀짝으로 두 색을 번갈아 고른다
        int color = groupIndex % GROUP_COLORS.length;
        // 받은 초가 0이면 통계가 없는 시각이다. 이때는 값 칸을 모두 비운다
        boolean empty = point.sampleSec() == 0;
        // 한 점 = 한 행. 스트리밍 시트는 행을 순서대로만 만들 수 있다
        Row row = sheet.createRow(nextRow++);
        row.createCell(0).setCellValue(at);
        row.getCell(0).setCellStyle(style(StyleKey.time(color)));
        setText(row, 1, series.sido(), color);
        setText(row, 2, series.sigungu(), color);
        setText(row, 3, series.emd(), color);
        setText(row, 4, series.zoneName(), color);
        int column = NAME_HEADERS.length;
        for (Column value : valueColumns) {
            // 통계가 없거나 그 항목 값이 null이면 칸을 비운다(0으로 쓰면 실제 0과 구분되지 않는다)
            BigDecimal number = empty ? null : value.value().apply(point);
            Cell cell = row.createCell(column);
            if (number != null) {
                cell.setCellValue(number.doubleValue());
            }
            // 빈 칸에도 서식을 붙여 배경색이 끊기지 않게 한다
            cell.setCellStyle(style(StyleKey.decimal(value.decimals(), color)));
            column++;
        }
    }

    /** 다 쓴 파일을 내보낸다. */
    // 통합 문서를 출력 스트림(HTTP 응답)에 쓴다
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
        // 대상 이름에 파일 이름으로 쓸 수 없는 글자가 있으면 '_'로 바꾼다
        String target = plan.targetName().replaceAll(UNSAFE_FILE_CHARS, "_");
        String from = plan.request().from().format(FILE_DATE);
        // to는 미만(미포함)이라, 실제로 마지막에 들어간 날을 구하려고 1분을 뺀다(예: 다음 날 0시면 전날)
        String to = plan.request().to().minusMinutes(1).format(FILE_DATE);
        return "stats_" + target + "_" + from + "_" + to + ".xlsx";
    }

    // 1m·1h는 분까지, 1d·1w는 날짜, 1M은 연-월, 1y는 연
    private static String timeFormat(StatInterval interval) {
        return switch (interval) {
                // 엑셀 서식 문자열이라 분은 소문자 mm(자바의 MM과 다르다)
            case MINUTE, HOUR -> "yyyy-mm-dd hh:mm";
            case DAY, WEEK -> "yyyy-mm-dd";
            case MONTH -> "yyyy-mm";
            case YEAR -> "yyyy";
        };
    }

    // 인원은 평균·최대·최소, 유입·유출은 방향(N·E·S·W) 합(통계 그래프와 같음)
    private static List<Column> columns(StatExportItem item) {
        return switch (item) {
                // 인원은 평균은 소수 둘째 자리, 최대·최소는 정수다
            case COUNT -> List.of(
                    new Column("평균 인원(명)", 2, StatSeriesPoint::countAvg),
                    // 최대·최소 인원은 정수(Integer)라 BigDecimal로 바꿔 같은 열 형식으로 처리한다
                    new Column("최대 인원(명)", 0, point -> toDecimal(point.countMax())),
                    new Column("최소 인원(명)", 0, point -> toDecimal(point.countMin())));
            case DENSITY -> List.of(new Column("밀집도(명/㎡)", 4, StatSeriesPoint::density));
            case INFLOW -> List.of(new Column("유입(명)", 0, point -> sum(point.inflow())));
            case OUTFLOW -> List.of(new Column("유출(명)", 0, point -> sum(point.outflow())));
            case DWELL -> List.of(new Column("평균 체류(초)", 1, StatSeriesPoint::dwellAvgSec));
        };
    }

    /** Integer를 BigDecimal로 바꾼다. null이면 null */
    private static BigDecimal toDecimal(Integer value) {
        return value == null ? null : BigDecimal.valueOf(value);
    }

    /** 방향별 인원(N·E·S·W)을 모두 더한다. 통계가 없는 점은 방향 정보가 null이라 null */
    private static BigDecimal sum(Map<String, Integer> flow) {
        return flow == null
                ? null
                : BigDecimal.valueOf(
                        flow.values().stream().mapToInt(Integer::intValue).sum());
    }

    // 같은 서식은 한 번만 만든다(엑셀은 서식 수에 제한이 있다)
    private CellStyle style(StyleKey key) {
        // 같은 (칸 종류, 색) 조합은 한 번만 만들어 재사용한다
        return styles.computeIfAbsent(key, ignored -> {
            XSSFCellStyle style = (XSSFCellStyle) workbook.createCellStyle();
            if (key.isTime()) {
                // 시각 칸: 단위에 맞는 날짜 형식
                style.setDataFormat(workbook.createDataFormat().getFormat(timeFormat));
            } else if (key.decimals() >= 0) {
                style.setDataFormat(workbook.createDataFormat()
                        // 값 칸: 천 단위 쉼표와 고정 소수 자릿수(0이면 정수)
                        .getFormat(key.decimals() == 0 ? "#,##0" : "#,##0." + "0".repeat(key.decimals())));
            }
            // 시각 묶음 색으로 칸 배경을 칠한다
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
        // 이름이 없는 단계(예: 시·도 합산 줄의 하위 단계)는 값을 쓰지 않고 칸만 만든다
        if (text != null) {
            cell.setCellValue(text);
        }
        cell.setCellStyle(style(StyleKey.text(color)));
    }

    // 스트리밍 시트는 자동 너비를 쓸 수 없어 고정 너비(글자 수 × 256)
    private static void setWidths(SXSSFSheet sheet, int columnCount) {
        // 엑셀의 열 너비 단위는 글자 수 × 256이다. 시각 열을 가장 넓게 둔다
        sheet.setColumnWidth(0, 18 * 256);
        for (int column = 1; column < columnCount; column++) {
            sheet.setColumnWidth(column, 14 * 256);
        }
    }

    /**
     * 칸 서식의 종류. decimals는 값 칸의 소수 자릿수, 시각 칸은 TIME(-2), 글자 칸은 TEXT(-1). color는 묶음 색 순서.
     */
    private record StyleKey(int decimals, int color) {

        // 시각 칸의 표식. 값 칸의 소수 자릿수(0 이상)와 겹치지 않도록 음수를 쓴다
        private static final int TIME = -2;
        // 글자 칸의 표식
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

        // 시각 칸이면 true
        boolean isTime() {
            return decimals == TIME;
        }
    }

    /** 항목 값 열 하나. */
    // header 머리글, decimals 소수 자릿수, value 점에서 이 열의 값을 꺼내는 함수(없으면 null)
    private record Column(String header, int decimals, Function<StatSeriesPoint, BigDecimal> value) {}
}
