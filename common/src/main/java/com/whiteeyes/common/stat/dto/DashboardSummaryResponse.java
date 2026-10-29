package com.whiteeyes.common.stat.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * GET /api/v1/dashboard/summary 응답(FNC-C-016). 최근 5분 안에 통계가 없으면 statAt부터 mainDirection까지 null이다.
 *
 * @param currentCount 5초마다 갱신되는 그 시점 인원의 합(FNC-A-054). 15초 안의 값이 없으면 null이고, 1분 통계가 없어도 값이 있을 수 있다
 * @param inflow 최근 1분 방향별 유입 합
 * @param mainDirection 유출이 가장 많은 방향(N/E/S/W). 유출이 없으면 null
 * @param todayMaxCount 오늘 최대 인원. 오늘 통계가 없으면 null
 */
public record DashboardSummaryResponse(
        OffsetDateTime statAt,
        BigDecimal countAvg,
        Integer currentCount,
        BigDecimal density,
        Integer level,
        Integer inflow,
        Integer outflow,
        String mainDirection,
        int todayEventCount,
        int cameraCount,
        int runningCameraCount,
        Integer todayMaxCount) {
}
