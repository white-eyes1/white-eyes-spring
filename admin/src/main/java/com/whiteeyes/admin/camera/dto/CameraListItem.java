package com.whiteeyes.admin.camera.dto;

import com.whiteeyes.common.camera.entity.CameraSourceType;
import com.whiteeyes.common.camera.entity.CameraStatus;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 캠 목록 한 줄(GET /api/v1/manage/cameras, FNC-A-022). progress는 준비 중일 때만 값이 있다.
 *
 * <p>stat·todayEventCount·recent10m은 1분 통계(FNC-A-028)·이벤트가 dev에 들어오면 채운다. 지금은 각각
 * null · 0 · 빈 목록으로 내려간다.
 */
public record CameraListItem(
        Long id,
        Long zoneId,
        String zoneName,
        String regionPath,
        double areaM2,
        String name,
        CameraSourceType sourceType,
        String sourceUri,
        CameraStatus status,
        Integer progress,
        String errorMessage,
        boolean runRequested,
        Stat stat,
        int todayEventCount,
        List<RecentCount> recent10m) {

    /** 최근 1분 통계(5분 안에 통계가 없으면 null). */
    public record Stat(LocalDateTime statAt, double countAvg, double density, int level) {}

    /** 최근 10분 1분 통계의 평균 인원 한 점. */
    public record RecentCount(LocalDateTime at, double countAvg) {}
}
