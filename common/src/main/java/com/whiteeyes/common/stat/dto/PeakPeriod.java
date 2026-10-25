package com.whiteeyes.common.stat.dto;

/**
 * 피크 타임 TOP 3의 기간(GET /api/v1/dashboard/peak-hours의 period, FNC-C-029 처리 순서 1). 모두 어제까지다.
 */
public enum PeakPeriod {
    /** 어제 하루 */
    DAY(1),
    /** 어제까지 7일 */
    WEEK(7),
    /** 어제까지 28일 */
    MONTH(28),
    /** 어제까지 364일 */
    YEAR(364);

    private final int days;

    PeakPeriod(int days) {
        this.days = days;
    }

    public int getDays() {
        return days;
    }
}
