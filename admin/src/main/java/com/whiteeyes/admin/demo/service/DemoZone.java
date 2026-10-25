package com.whiteeyes.admin.demo.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

import com.whiteeyes.common.region.entity.ZoneDirection;

/**
 * 시연용 통계를 만들 구역 하나(FNC-A-051).
 *
 * @param id 구역 번호
 * @param areaM2 면적(밀집도 계산)
 * @param baseCount 기본 인원
 * @param avgDwellSec 평균 체류(초)
 * @param endDate 만들 마지막 날짜. 캠 가동 시작 전날(없으면 어제)
 * @param neighborIds 방향 → 이웃 구역 번호. 없는 방향은 관측 범위 밖
 */
record DemoZone(
        long id,
        BigDecimal areaM2,
        double baseCount,
        int avgDwellSec,
        LocalDate endDate,
        Map<ZoneDirection, Long> neighborIds) {

    boolean covers(LocalDate startDate, LocalDate date) {
        return !date.isBefore(startDate) && !date.isAfter(endDate);
    }
}
