package com.whiteeyes.common.stat.dto;

/**
 * 기간 비교의 기준 기간 프리셋(FNC-C-020 업무 규칙). 같은 요일끼리 맞도록 7의 배수 일만큼 앞으로 옮긴다.
 */
public enum BasePreset {
    /** 지난달: 대상 기간을 28일 앞으로 */
    LAST_MONTH(28),
    /** 지난해 같은 기간: 대상 기간을 364일 앞으로 */
    LAST_YEAR(364);

    private final int shiftDays;

    BasePreset(int shiftDays) {
        this.shiftDays = shiftDays;
    }

    public int getShiftDays() {
        return shiftDays;
    }
}
