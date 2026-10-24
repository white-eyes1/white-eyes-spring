package com.whiteeyes.admin.event.service;

import com.whiteeyes.admin.event.dto.EventExportRow;
import com.whiteeyes.admin.event.dto.EventListItem;
import com.whiteeyes.common.event.entity.EventStatus;
import com.whiteeyes.common.event.entity.EventType;
import com.whiteeyes.common.level.service.CongestionLevel;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Map;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataFormat;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.streaming.SXSSFSheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;

/**
 * 이벤트 엑셀 파일을 쓴다(FNC-A-039 처리 순서 2). SXSSF(스트리밍)라 메모리에는 최근 행 몇 개만 두고 나머지는 임시 파일로
 * 내린다. 시트 하나에 이벤트 한 건이 한 행이다. 다 쓰면 {@link #close()}로 임시 파일을 지운다.
 *
 * <p>열: 발생·종료 시각, 지속, 종류, 시·군·구, 읍·면·동, 구역, 최고 단계, 최고 밀집도(혼잡), 최고 증가율(급증), 처리 상태,
 * 조치 항목·메모, 처리자, 처리 일시. 종류에 해당하지 않는 값 칸은 비운다. 진행 중이면 종료 시각 칸에 '진행 중'을 쓴다.
 */
public class EventExcelWriter implements AutoCloseable {

    public static final String SHEET_NAME = "이벤트";

    // 메모리에 둘 행 수(SXSSF 기본값)
    private static final int WINDOW_SIZE = 100;
    private static final String[] HEADERS = {
        "발생 시각", "종료 시각", "지속(분)", "종류", "시·군·구", "읍·면·동", "구역", "최고 단계", "최고 밀집도(명/㎡)", "최고 증가율(%)",
        "처리 상태", "조치 항목", "조치 메모", "처리자", "처리 일시"
    };
    private static final String ONGOING_TEXT = "진행 중";
    private static final String REGION_SEPARATOR = " · ";
    private static final int SECONDS_PER_MINUTE = 60;

    // 화면 표시 이름(conventions.md 코드 값)
    private static final Map<EventType, String> TYPE_LABELS =
            Map.of(EventType.CONGESTION, "혼잡", EventType.SURGE, "급증", EventType.CAM_FAULT, "캠 이상");
    private static final Map<EventStatus, String> STATUS_LABELS =
            Map.of(EventStatus.UNCHECKED, "미확인", EventStatus.CHECKED, "확인 중", EventStatus.RESOLVED, "조치완료");
    private static final Map<String, String> ACTION_LABELS = Map.of(
            "ONSITE_OK", "현장 확인·정상",
            "STAFF", "인력 배치",
            "FALSE_ALARM", "오탐",
            "CAM_CHECK", "캠 점검 요청",
            "OTHER", "기타");

    private final SXSSFWorkbook workbook = new SXSSFWorkbook(WINDOW_SIZE);
    private final SXSSFSheet sheet;
    private final CellStyle timeStyle;
    private final CellStyle minuteStyle;
    private final CellStyle valueStyle;
    private int nextRow = 1;

    public EventExcelWriter() {
        workbook.setCompressTempFiles(true);
        sheet = workbook.createSheet(SHEET_NAME);

        DataFormat format = workbook.createDataFormat();
        timeStyle = workbook.createCellStyle();
        timeStyle.setDataFormat(format.getFormat("yyyy-mm-dd hh:mm"));
        minuteStyle = workbook.createCellStyle();
        minuteStyle.setDataFormat(format.getFormat("#,##0.0"));
        valueStyle = workbook.createCellStyle();
        valueStyle.setDataFormat(format.getFormat("#,##0.00##"));

        CellStyle headerStyle = workbook.createCellStyle();
        Font bold = workbook.createFont();
        bold.setBold(true);
        headerStyle.setFont(bold);
        Row header = sheet.createRow(0);
        for (int column = 0; column < HEADERS.length; column++) {
            header.createCell(column).setCellValue(HEADERS[column]);
            header.getCell(column).setCellStyle(headerStyle);
            // 스트리밍 시트는 자동 너비를 쓸 수 없어 고정 너비(글자 수 × 256)
            sheet.setColumnWidth(column, widthOf(column) * 256);
        }
        sheet.createFreezePane(0, 1);
    }

    /** 이벤트 한 건을 한 행으로 쓴다. 부르는 순서가 행 순서다. */
    public void append(EventExportRow exportRow) {
        EventListItem item = exportRow.item();
        Row row = sheet.createRow(nextRow++);
        setTime(row, 0, item.startedAt().toLocalDateTime());
        if (item.ongoing() || item.endedAt() == null) {
            setText(row, 1, ONGOING_TEXT);
        } else {
            setTime(row, 1, item.endedAt().toLocalDateTime());
        }
        row.createCell(2).setCellValue((double) item.durationSec() / SECONDS_PER_MINUTE);
        row.getCell(2).setCellStyle(minuteStyle);
        setText(row, 3, TYPE_LABELS.getOrDefault(item.type(), item.type().name()));
        setRegion(row, item.regionPath());
        setText(row, 6, item.zoneName());
        setText(row, 7, levelName(item.peakLevel()));
        // 혼잡은 밀집도(명/㎡), 급증은 증가율(%)이 같은 peak_value 칸에 들어 있어 종류로 나눠 쓴다
        if (item.type() == EventType.CONGESTION) {
            setNumber(row, 8, item.peakValue());
        } else if (item.type() == EventType.SURGE) {
            setNumber(row, 9, item.peakValue());
        }
        setText(row, 10, STATUS_LABELS.getOrDefault(item.status(), item.status().name()));
        setText(row, 11, actionLabel(exportRow.actionCode()));
        setText(row, 12, exportRow.actionNote());
        setText(row, 13, exportRow.handlerLoginId());
        if (exportRow.handledAt() != null) {
            setTime(row, 14, exportRow.handledAt().toLocalDateTime());
        }
    }

    /** 다 쓴 파일을 내보낸다. */
    public void writeTo(OutputStream out) throws IOException {
        workbook.write(out);
    }

    /** 임시 파일을 지운다. */
    @Override
    public void close() throws IOException {
        workbook.close();
        workbook.dispose();
    }

    private void setTime(Row row, int column, LocalDateTime time) {
        row.createCell(column).setCellValue(time);
        row.getCell(column).setCellStyle(timeStyle);
    }

    private void setNumber(Row row, int column, BigDecimal value) {
        if (value != null) {
            row.createCell(column).setCellValue(value.doubleValue());
            row.getCell(column).setCellStyle(valueStyle);
        }
    }

    private static void setText(Row row, int column, String text) {
        if (text != null) {
            row.createCell(column).setCellValue(text);
        }
    }

    // 지역 경로는 "시·군·구 · 읍·면·동"(목록과 같은 문자열)이라 두 칸으로 나눈다
    private static void setRegion(Row row, String regionPath) {
        if (regionPath == null) {
            return;
        }
        String[] names = regionPath.split(REGION_SEPARATOR, 2);
        if (names.length == 2) {
            setText(row, 4, names[0]);
            setText(row, 5, names[1]);
        } else {
            setText(row, 5, names[0]);
        }
    }

    private static String levelName(Integer level) {
        if (level == null) {
            return null;
        }
        return Arrays.stream(CongestionLevel.values())
                .filter(candidate -> candidate.getLevel() == level)
                .map(CongestionLevel::getDisplayName)
                .findFirst()
                .orElse(String.valueOf(level));
    }

    private static String actionLabel(String actionCode) {
        return actionCode == null ? null : ACTION_LABELS.getOrDefault(actionCode, actionCode);
    }

    private static int widthOf(int column) {
        return switch (column) {
            case 0, 1, 14 -> 18;
            case 12 -> 30;
            case 8 -> 20;
            default -> 14;
        };
    }
}
