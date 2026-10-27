package com.whiteeyes.admin.demo.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import com.whiteeyes.common.region.entity.ZoneDirection;

/**
 * 시연용 통계를 만들 수 있는 구역 하나(FNC-A-051). 시각은 KST 정시다.
 *
 * @param id 구역 번호
 * @param areaM2 면적(밀집도 계산)
 * @param baseCount 기본 인원
 * @param avgDwellSec 평균 체류(초)
 * @param start 만들 첫 시각. 끝 시각의 1년 전 날짜 0시
 * @param end 끝 시각(이 시각은 만들지 않음). 캠 첫 가동 시각이 속한 정시, 가동한 적 없으면 실행 시각이 속한 정시
 * @param neighborIds 방향 → 이웃 구역 번호. 없는 방향은 관측 범위 밖
 */
record DemoZone(
        long id,
        BigDecimal areaM2,
        double baseCount,
        int avgDwellSec,
        LocalDateTime start,
        LocalDateTime end,
        Map<ZoneDirection, Long> neighborIds) {

    /**
     * @param firstStartedAt 캠 첫 가동 시각. 없으면 null
     * @param now 실행 시각(KST)
     */
    static DemoZone of(
            long id,
            BigDecimal areaM2,
            double baseCount,
            int avgDwellSec,
            LocalDateTime firstStartedAt,
            LocalDateTime now,
            Map<ZoneDirection, Long> neighborIds) {
        LocalDateTime end = (firstStartedAt == null ? now : firstStartedAt).truncatedTo(ChronoUnit.HOURS);
        LocalDateTime start = end.minusYears(1).truncatedTo(ChronoUnit.DAYS);
        return new DemoZone(id, areaM2, baseCount, avgDwellSec, start, end, neighborIds);
    }

    boolean covers(LocalDateTime hour) {
        return !hour.isBefore(start) && hour.isBefore(end);
    }
}
