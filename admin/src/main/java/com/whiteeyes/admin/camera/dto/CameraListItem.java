package com.whiteeyes.admin.camera.dto;

import com.whiteeyes.common.camera.entity.CameraSourceType;
import com.whiteeyes.common.camera.entity.CameraStatus;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 캠 목록 한 줄(GET /api/v1/manage/cameras, FNC-A-022). progress는 준비 중일 때만 값이 있다.
 *
 * <p>stat은 5분 안에 1분 통계가 없으면 null, recent10m은 최근 10분의 1분 통계(평균 인원·밀집도·혼잡 단계, 시각 순서, 최대 10개)이다.
 * recent10m의 level은 stat과 같은 기준(FNC-C-025)이라 같은 분의 GET /api/v1/stats/series(1m) level과 같다(결정 0016).
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

    /** 최근 10분 1분 통계 한 점: 평균 인원, 밀집도, 그 밀집도로 판정한 혼잡 단계(캠 카드 단계색 막대). */
    public record RecentCount(OffsetDateTime at, double countAvg, double density, int level) {}
}
