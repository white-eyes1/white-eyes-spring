package com.whiteeyes.common.stat.dto;

/**
 * 기간 비교의 기준 기간 프리셋(FNC-C-020 업무 규칙). 같은 요일끼리 맞도록 7의 배수 일만큼 앞으로 옮긴다.
 */
public enum BasePreset {
    /** 지난달: 대상 기간을 28일 앞으로 */
    LAST_MONTH(28),
    /** 지난해 같은 기간: 대상 기간을 364일 앞으로 */
    LAST_YEAR(364);

    // 기준 기간을 만들 때 대상 기간에서 빼는 일수. 7의 배수여야 요일이 어긋나지 않는다
    private final int shiftDays;

    // enum 상수(LAST_MONTH, LAST_YEAR)가 생성될 때 앞으로 옮길 일수를 받아 둔다
    BasePreset(int shiftDays) {
        this.shiftDays = shiftDays;
    }

    // CompareRequest가 대상 기간을 이 일수만큼 앞으로 옮겨 기준 기간을 만든다
    public int getShiftDays() {
        return shiftDays;
    }
}
