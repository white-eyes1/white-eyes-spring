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

    // 어제부터 거슬러 올라가 세는 일수
    private final int days;

    // enum 상수마다 조회할 일수를 받아 둔다
    PeakPeriod(int days) {
        this.days = days;
    }

    // PeakHourService가 어제까지 이 일수만큼의 시간별 통계를 읽을 때 쓴다
    public int getDays() {
        return days;
    }
}
