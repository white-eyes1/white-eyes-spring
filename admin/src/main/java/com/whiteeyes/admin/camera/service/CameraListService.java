package com.whiteeyes.admin.camera.service;

import com.whiteeyes.admin.camera.dto.CameraListItem;
import com.whiteeyes.admin.region.service.RegionLookupService;
import com.whiteeyes.admin.region.dto.ZoneSummary;
import com.whiteeyes.admin.region.service.ZoneService;
import com.whiteeyes.common.camera.entity.Camera;
import com.whiteeyes.common.camera.entity.CameraStatus;
import com.whiteeyes.common.camera.repository.CameraRepository;
import com.whiteeyes.common.event.service.TodayEventCountService;
import com.whiteeyes.common.level.service.LevelService;
import com.whiteeyes.common.level.service.LevelTarget;
import com.whiteeyes.common.stat.dto.ZoneMinuteStat;
import com.whiteeyes.common.stat.entity.ZoneNowCount;
import com.whiteeyes.common.stat.service.NowCountReader;
import com.whiteeyes.common.stat.service.ZoneStatQueryService;
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

/** 모든 캠의 상태·진행률과 구역 정보를 한 번에 모아 준다(FNC-A-022 캠 목록). */
@Service
@RequiredArgsConstructor
public class CameraListService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final int STAT_FRESH_MINUTES = 5;
    private static final int RECENT_MINUTES = 10;

    private final CameraRepository cameraRepository;
    private final ZoneService zoneService;
    private final RegionLookupService regionLookupService;
    private final ZoneStatQueryService zoneStatQueryService;
    private final LevelService levelService;
    private final TodayEventCountService todayEventCountService;
    private final NowCountReader nowCountReader;

    /**
     * 삭제되지 않은 캠 목록을 번호 순으로 돌려준다. regionId가 있으면 그 지역 아래 구역의 캠만 돌려준다.
     * 캠마다 따로 조회하지 않고 캠 → 구역 → 지역 경로 → 구역들의 최근 1분 통계를 각각 한 번의 쿼리로 모은다.
     */
    @Transactional(readOnly = true)
    public List<CameraListItem> list(Long regionId) {
        List<Camera> cameras = regionId == null ? cameraRepository.findByDeletedAtIsNullOrderByIdAsc() : findInRegion(regionId);
        if (cameras.isEmpty()) {
            return List.of();
        }

        Set<Long> zoneIds = cameras.stream().map(Camera::getZoneId).collect(Collectors.toSet());
        Map<Long, ZoneSummary> zones = zoneService.findSummaries(zoneIds);
        Set<Long> dongIds = zones.values().stream().map(ZoneSummary::dongId).collect(Collectors.toSet());
        Map<Long, String> regionPaths = regionLookupService.findPaths(dongIds);
        // 최근 10분의 1분 통계를 한 번에 가져와 stat(5분 안의 가장 최근 것)과 recent10m 둘 다에 쓴다
        LocalDateTime now = LocalDateTime.now(KST);
        Map<Long, List<ZoneMinuteStat>> stats = zoneStatQueryService.findRecentByZone(zoneIds, now.minusMinutes(RECENT_MINUTES));
        // 오늘 혼잡·급증 이벤트 수도 구역 전체를 한 번에 센다(FNC-C-017). 오늘은 KST 00:00부터
        Map<Long, Integer> eventCounts =
                todayEventCountService.countByZone(zoneIds, LocalDate.now(KST).atStartOfDay());
        // 5초마다 갱신되는 현재 인원(FNC-A-054)도 구역 전체를 한 번에 읽는다
        Map<Long, ZoneNowCount> nowCounts = nowCountReader.findFresh(zoneIds, now);

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

    private CameraListItem toItem(
            Camera camera,
            ZoneSummary zone,
            Map<Long, String> regionPaths,
            List<ZoneMinuteStat> recent,
            int todayEventCount,
            ZoneNowCount nowCount,
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
                camera.getStatus() == CameraStatus.PREPARING ? camera.getProgress() : null,
                camera.getErrorMessage(),
                camera.isRunRequested(),
                toStat(camera.getZoneId(), recent, nowCount, now),
                todayEventCount,
                toRecent10m(camera.getZoneId(), recent));
    }

    /** 5분 안에 1분 통계가 없으면 null. 있으면 가장 최근 것과 혼잡 단계(FNC-C-025). */
    private CameraListItem.Stat toStat(
            Long zoneId, List<ZoneMinuteStat> recent, ZoneNowCount nowCount, LocalDateTime now) {
        if (recent.isEmpty()) {
            return null;
        }
        ZoneMinuteStat latest = recent.get(recent.size() - 1);
        if (latest.statAt().isBefore(now.minusMinutes(STAT_FRESH_MINUTES))) {
            return null;
        }
        int level = levelService.judgeLevel(LevelTarget.zone(zoneId), latest.density()).getLevel();
        Integer currentCount = nowCount == null ? null : nowCount.getPersonCount();
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
        List<ZoneMinuteStat> sampled = recent.stream().filter(stat -> stat.sampleSec() > 0).toList();
        return sampled.stream()
                .skip(Math.max(0, sampled.size() - RECENT_MINUTES))
                .map(stat -> new CameraListItem.RecentCount(
                        toKstOffset(stat.statAt()),
                        stat.countAvg().doubleValue(),
                        stat.density().doubleValue(),
                        levelService.judgeLevel(LevelTarget.zone(zoneId), stat.density()).getLevel()))
                .toList();
    }
}
