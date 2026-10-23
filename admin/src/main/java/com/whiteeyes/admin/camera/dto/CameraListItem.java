package com.whiteeyes.admin.camera.dto;

import com.whiteeyes.common.camera.entity.CameraSourceType;
import com.whiteeyes.common.camera.entity.CameraStatus;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 캠 목록 한 줄(GET /api/v1/manage/cameras, FNC-A-022). progress는 준비 중일 때만 값이 있다.
 *
 * <p>stat은 5분 안에 1분 통계가 없으면 null, recent10m은 최근 10분의 1분 평균 인원(시각 순서, 최대 10개)이다.
 * todayEventCount는 오늘(KST 00:00 이후) 시작한 그 구역의 혼잡·급증 이벤트 수이고, 캠 이상은 세지 않는다.
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

    /** 최근 1분 통계(5분 안에 통계가 없으면 null). 시각은 한국 시간 오프셋을 붙여 내려간다(conventions.md '시각 형식'). */
    public record Stat(OffsetDateTime statAt, double countAvg, double density, int level) {}

    /** 최근 10분 1분 통계의 평균 인원 한 점. */
    public record RecentCount(OffsetDateTime at, double countAvg) {}
}
