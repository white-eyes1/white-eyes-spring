package com.whiteeyes.common.stat.dto;

import java.util.Arrays;
import java.util.Optional;

/**
 * 통계 엑셀에 담을 항목(GET /api/v1/stats/export의 items, conventions.md '통계 항목'). 고른 항목은 한 시트('통계')에 선언 순서대로 열이 된다.
 */
public enum StatExportItem {
    COUNT("인원"),
    DENSITY("밀집도"),
    INFLOW("유입"),
    OUTFLOW("유출"),
    DWELL("평균 체류");

    private final String label;

    StatExportItem(String label) {
        this.label = label;
    }

    /** 요청 값(COUNT 등)으로 찾는다. 없는 값이면 빈 값. */
    public static Optional<StatExportItem> fromCode(String code) {
        return Arrays.stream(values()).filter(item -> item.name().equals(code)).findFirst();
    }

    public String getLabel() {
        return label;
    }
}
