package com.whiteeyes.admin.camera.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.whiteeyes.admin.camera.dto.CameraListItem;
import com.whiteeyes.admin.region.dto.ZoneSummary;
import com.whiteeyes.admin.region.service.RegionLookupService;
import com.whiteeyes.admin.region.service.ZoneService;
import com.whiteeyes.common.camera.entity.Camera;
import com.whiteeyes.common.camera.entity.CameraStatus;
import com.whiteeyes.common.camera.repository.CameraRepository;
import com.whiteeyes.common.event.service.TodayEventCountService;
import com.whiteeyes.common.level.service.LevelService;
import com.whiteeyes.common.level.service.LevelTarget;
import com.whiteeyes.common.stat.dto.ZoneMinuteStat;
import com.whiteeyes.common.stat.service.NowCountSource;
import com.whiteeyes.common.stat.service.NowCountSource.NowCount;
import com.whiteeyes.common.stat.service.ZoneStatQueryService;

/** 모든 캠의 상태·진행률과 구역 정보를 한 번에 모아 준다(FNC-A-022 캠 목록). */
@Service
@RequiredArgsConstructor
public class CameraListService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    // stat 필드로 인정하는 1분 통계의 최대 나이(분). 이보다 오래된 통계만 있으면 '통계 없음'으로 본다.
    private static final int STAT_FRESH_MINUTES = 5;
    // recent10m 그래프에 쓰는 최근 구간(분)이자 최대 점 수.
    private static final int RECENT_MINUTES = 10;

    // 아래 서비스들은 모두 '여러 구역을 한 번에' 조회하는 메서드를 제공해, 캠 수만큼 쿼리가 나가는 N+1을 피한다.
    private final CameraRepository cameraRepository;
    private final ZoneService zoneService;
    private final RegionLookupService regionLookupService;
    private final ZoneStatQueryService zoneStatQueryService;
    private final LevelService levelService;
    private final TodayEventCountService todayEventCountService;
    // 5초마다 갱신되는 현재 인원을 메모리에서 읽는 출처(DB가 아님).
    private final NowCountSource nowCountSource;

    /**
     * 삭제되지 않은 캠 목록을 번호 순으로 돌려준다. regionId가 있으면 그 지역 아래 구역의 캠만 돌려준다.
     * 캠마다 따로 조회하지 않고 캠 → 구역 → 지역 경로 → 구역들의 최근 1분 통계를 각각 한 번의 쿼리로 모은다.
     */
    @Transactional(readOnly = true)
    public List<CameraListItem> list(Long regionId) {
        List<Camera> cameras =
                regionId == null ? cameraRepository.findByDeletedAtIsNullOrderByIdAsc() : findInRegion(regionId);
        // 캠이 없으면 이후 IN 조회를 모두 건너뛴다(빈 목록으로 IN 절을 만들지 않기 위함).
        if (cameras.isEmpty()) {
            return List.of();
        }

        // 캠이 속한 구역 ID 모음. 이후 구역·통계 조회의 키로 쓴다.
        Set<Long> zoneIds = cameras.stream().map(Camera::getZoneId).collect(Collectors.toSet());
        // 구역 이름·면적·동(行政洞) ID를 한 번에 가져온다.
        Map<Long, ZoneSummary> zones = zoneService.findSummaries(zoneIds);
        Set<Long> dongIds = zones.values().stream().map(ZoneSummary::dongId).collect(Collectors.toSet());
        // 동 ID별 '시·구·동' 경로 문자열(화면의 지역 표기).
        Map<Long, String> regionPaths = regionLookupService.findPaths(dongIds);
        // 최근 10분의 1분 통계를 한 번에 가져와 stat(5분 안의 가장 최근 것)과 recent10m 둘 다에 쓴다
        LocalDateTime now = LocalDateTime.now(KST);
        Map<Long, List<ZoneMinuteStat>> stats =
                zoneStatQueryService.findRecentByZone(zoneIds, now.minusMinutes(RECENT_MINUTES));
        // 오늘 혼잡·급증 이벤트 수도 구역 전체를 한 번에 센다(FNC-C-017). 오늘은 KST 00:00부터
        Map<Long, Integer> eventCounts =
                todayEventCountService.countByZone(zoneIds, LocalDate.now(KST).atStartOfDay());
        // 5초마다 갱신되는 현재 인원(FNC-A-054)은 메모리 누적기에서 구역 전체를 한 번에 읽는다
        Map<Long, NowCount> nowCounts = nowCountSource.findFresh(zoneIds, now);

        // 캠마다 미리 모아 둔 구역·통계·이벤트 수·현재 인원을 메모리에서 골라 한 줄로 조립한다. 통계가 없는 구역은 빈 목록/0으로 대체한다.
        return cameras.stream()
                .map(camera -> toItem(
                        camera,
                        zones.get(camera.getZoneId()),
                        regionPaths,
                        stats.getOrDefault(camera.getZoneId(), List.of()),
                        eventCounts.getOrDefault(camera.getZoneId(), 0),
                        nowCounts.get(camera.getZoneId()),
                        now))
                .toList();
    }

    /**
     * 지역(시·구·동 어느 단계든)에 속한 구역의 캠을 찾는다. 지역 -> 하위 동 -> 활성 구역 -> 캠 순서로 좁히고,
     * 중간에 비면 곧바로 빈 목록을 돌려준다.
     */
    private List<Camera> findInRegion(Long regionId) {
        List<Long> dongIds = regionLookupService.findDongIdsUnder(regionId);
        if (dongIds.isEmpty()) {
            return List.of();
        }
        List<Long> zoneIds = zoneService.findActiveZoneIdsInDongs(dongIds);
        if (zoneIds.isEmpty()) {
            return List.of();
        }
        return cameraRepository.findByZoneIdInAndDeletedAtIsNullOrderByIdAsc(zoneIds);
    }

    /**
     * 캠 한 건과 미리 모아 둔 부가 정보로 목록 한 줄을 만든다. 구역이 사라진 경우(zone이 null)에도 목록이
     * 깨지지 않도록 이름·지역은 null, 면적은 0으로 둔다.
     */
    private CameraListItem toItem(
            Camera camera,
            ZoneSummary zone,
            Map<Long, String> regionPaths,
            List<ZoneMinuteStat> recent,
            int todayEventCount,
            NowCount nowCount,
            LocalDateTime now) {
        return new CameraListItem(
                camera.getId(),
                camera.getZoneId(),
                zone == null ? null : zone.name(),
                zone == null ? null : regionPaths.get(zone.dongId()),
                zone == null ? 0 : zone.areaM2().doubleValue(),
                camera.getName(),
                camera.getSourceType(),
                camera.getSourceUri(),
                camera.getStatus(),
                // 진행률은 준비 중일 때만 의미가 있어서 그 외 상태에서는 null로 내린다.
                camera.getStatus() == CameraStatus.PREPARING ? camera.getProgress() : null,
                camera.getErrorMessage(),
                camera.isRunRequested(),
                toStat(camera.getZoneId(), recent, nowCount, now),
                todayEventCount,
                toRecent10m(camera.getZoneId(), recent));
    }

    /** 5분 안에 1분 통계가 없으면 null. 있으면 가장 최근 것과 혼잡 단계(FNC-C-025). */
    private CameraListItem.Stat toStat(Long zoneId, List<ZoneMinuteStat> recent, NowCount nowCount, LocalDateTime now) {
        if (recent.isEmpty()) {
            return null;
        }
        // 목록은 시각 오름차순이므로 마지막이 가장 최근 통계다.
        ZoneMinuteStat latest = recent.get(recent.size() - 1);
        if (latest.statAt().isBefore(now.minusMinutes(STAT_FRESH_MINUTES))) {
            return null;
        }
        // 밀집도를 혼잡 단계로 바꾼다. 구역별 기준이 다를 수 있어 구역 대상으로 판정한다.
        int level = levelService
                .judgeLevel(LevelTarget.zone(zoneId), latest.density())
                .getLevel();
        // 5초 갱신 현재 인원이 15초 안에 없으면 null(화면은 SSE로 받는다).
        Integer currentCount = nowCount == null ? null : nowCount.personCount();
        return new CameraListItem.Stat(
                toKstOffset(latest.statAt()),
                latest.countAvg().doubleValue(),
                currentCount,
                latest.density().doubleValue(),
                level);
    }

    // DB에는 KST를 오프셋 없이 저장하므로 응답에서 +09:00을 붙인다
    private static OffsetDateTime toKstOffset(LocalDateTime time) {
        return time.atZone(KST).toOffsetDateTime();
    }

    /** 최근 10분의 1분 통계(평균 인원·밀집도·혼잡 단계)를 시각 순서로 최대 10개. */
    // 분마다 stat과 같은 기준으로 단계를 판정한다(카드의 단계색 막대, 결정 0016).
    // 결과가 들어온 초(sample_sec)가 0인 분은 통계 그래프(FNC-C-012)에서 빈 점이므로 여기서도 뺀다
    private List<CameraListItem.RecentCount> toRecent10m(Long zoneId, List<ZoneMinuteStat> recent) {
        // 결과가 들어온 초(sample_sec)가 0이면 그 분은 측정이 없던 분이라 그래프에서 제외한다.
        List<ZoneMinuteStat> sampled =
                recent.stream().filter(stat -> stat.sampleSec() > 0).toList();
        return sampled.stream()
                // 최근 10개만 남기려고 앞쪽을 건너뛴다.
                .skip(Math.max(0, sampled.size() - RECENT_MINUTES))
                .map(stat -> new CameraListItem.RecentCount(
                        toKstOffset(stat.statAt()),
                        stat.countAvg().doubleValue(),
                        stat.density().doubleValue(),
                        levelService
                                .judgeLevel(LevelTarget.zone(zoneId), stat.density())
                                .getLevel()))
                .toList();
    }
}
