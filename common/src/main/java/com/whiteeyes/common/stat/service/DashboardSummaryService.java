package com.whiteeyes.common.stat.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.whiteeyes.common.camera.service.CameraCountService;
import com.whiteeyes.common.level.service.LevelService;
import com.whiteeyes.common.level.service.LevelTarget;
import com.whiteeyes.common.region.entity.Region;
import com.whiteeyes.common.region.entity.Zone;
import com.whiteeyes.common.region.entity.ZoneDirection;
import com.whiteeyes.common.region.entity.ZoneNeighbor;
import com.whiteeyes.common.region.repository.ZoneNeighborRepository;
import com.whiteeyes.common.region.service.RegionQueryService;
import com.whiteeyes.common.stat.dto.DashboardSummaryResponse;
import com.whiteeyes.common.stat.dto.DashboardTargetType;
import com.whiteeyes.common.stat.entity.ZoneStatMinute;
import com.whiteeyes.common.stat.repository.ZoneStatMinuteRepository;
import com.whiteeyes.common.stat.service.RecentZoneStatReader.LatestMinuteSum;

/**
 * 현재 현황 요약(FNC-C-016). 선택한 구역·지역의 최근 1분 인원·밀집도·단계·유입/유출·주요 방향과 캠 수, 오늘 최대 인원을 돌려준다.
 */
// TODO(FNC-C-017): 3단계에서 오늘 혼잡·급증 이벤트 수를 채운다. 1단계는 0(이슈 #21 범위 밖)
@Service
public class DashboardSummaryService {

    private static final ZoneOffset KST_OFFSET = ZoneOffset.ofHours(9);
    // 유출이 같으면 앞의 방향(FNC-C-016 처리 순서 2)
    private static final List<String> DIRECTIONS = List.of("N", "E", "S", "W");

    private final RegionQueryService regionQueryService;
    private final CameraCountService cameraCountService;
    private final RecentZoneStatReader recentZoneStatReader;
    private final ZoneStatMinuteRepository zoneStatMinuteRepository;
    private final LevelService levelService;
    private final ZoneNeighborRepository zoneNeighborRepository;

    public DashboardSummaryService(
            RegionQueryService regionQueryService,
            CameraCountService cameraCountService,
            RecentZoneStatReader recentZoneStatReader,
            ZoneStatMinuteRepository zoneStatMinuteRepository,
            LevelService levelService,
            ZoneNeighborRepository zoneNeighborRepository) {
        this.regionQueryService = regionQueryService;
        this.cameraCountService = cameraCountService;
        this.recentZoneStatReader = recentZoneStatReader;
        this.zoneStatMinuteRepository = zoneStatMinuteRepository;
        this.levelService = levelService;
        this.zoneNeighborRepository = zoneNeighborRepository;
    }

    /**
     * @param now KST 현재 시각
     * @throws com.whiteeyes.common.region.exception.RegionNotFoundException 없는 지역이면(404)
     * @throws com.whiteeyes.common.region.exception.ZoneNotFoundException 없거나 삭제된 구역이면(404)
     */
    @Transactional(readOnly = true)
    public DashboardSummaryResponse findSummary(DashboardTargetType targetType, long targetId, LocalDateTime now) {
        Target target = targetType == DashboardTargetType.ZONE ? zoneTarget(targetId) : regionTarget(targetId);
        List<Long> activeZoneIds = target.activeZones().stream().map(Zone::getId).toList();
        int cameraCount = cameraCountService.countCameras(activeZoneIds);
        int runningCameraCount = cameraCountService.countRunningCameras(activeZoneIds);
        Integer todayMaxCount = findTodayMaxCount(target.allZoneIds(), now);

        Map<Long, ZoneStatMinute> latestStatByZone = recentZoneStatReader.findLatestStats(target.activeZones(), now);
        return recentZoneStatReader
                .sumAtLatestMinute(target.activeZones(), latestStatByZone)
                .map(sum -> toResponse(target, sum, cameraCount, runningCameraCount, todayMaxCount))
                .orElseGet(() -> new DashboardSummaryResponse(
                        null, null, null, null, null, null, null, 0, cameraCount, runningCameraCount, todayMaxCount));
    }

    private Target zoneTarget(long zoneId) {
        Zone zone = regionQueryService.getActiveZone(zoneId);
        return new Target(LevelTarget.zone(zoneId), List.of(zone), List.of(zoneId), true);
    }

    // 현재 값·캠 수는 삭제되지 않은 구역으로, 오늘 기록은 삭제된 구역까지 포함해 센다(FNC-C-017과 같은 기준)
    private Target regionTarget(long regionId) {
        Region region = regionQueryService.getRegion(regionId);
        List<Long> dongIds = regionQueryService.findEmdsUnder(region).stream().map(Region::getId).toList();
        if (dongIds.isEmpty()) {
            return new Target(LevelTarget.region(regionId), List.of(), List.of(), false);
        }
        return new Target(
                LevelTarget.region(regionId),
                regionQueryService.findZonesInDongs(dongIds),
                regionQueryService.findAllZoneIdsInDongs(dongIds),
                false);
    }

    // 같은 시각끼리 소속 구역 count_max를 더한 값 중 오늘 최댓값(FNC-C-016 처리 순서 5). 구역 하나면 그 구역의 count_max 최댓값
    private Integer findTodayMaxCount(List<Long> zoneIds, LocalDateTime now) {
        if (zoneIds.isEmpty()) {
            return null;
        }
        return zoneStatMinuteRepository.sumCountMaxByStatAt(zoneIds, now.toLocalDate().atStartOfDay()).stream()
                .max(Long::compare)
                .map(Long::intValue)
                .orElse(null);
    }

    private DashboardSummaryResponse toResponse(
            Target target, LatestMinuteSum sum, int cameraCount, int runningCameraCount, Integer todayMaxCount) {
        // 구역 하나면 저장된 밀집도(당시 면적 기준)를 그대로 쓴다. 지역은 현재 면적 합으로 나눈 값(#20과 같은 규칙)
        BigDecimal density = target.isZone() ? sum.stats().get(0).getDensity() : sum.density();
        int[] inByDirection = new int[DIRECTIONS.size()];
        int[] outByDirection = new int[DIRECTIONS.size()];
        // 지역은 지역 안 구역끼리 주고받은 이동을 뺀다(stats-008): 이웃이 같은 지역 구역인 방향의 유입·유출은 세지 않는다.
        // 구역 하나는 이웃이 자기 밖이므로 그대로 센다
        Map<Long, Map<ZoneDirection, Long>> neighborsByZone =
                target.isZone() ? Map.of() : findNeighbors(sum.stats());
        Set<Long> targetZoneIds = new HashSet<>();
        target.activeZones().forEach(zone -> targetZoneIds.add(zone.getId()));
        for (ZoneStatMinute stat : sum.stats()) {
            Map<ZoneDirection, Long> neighbors = neighborsByZone.getOrDefault(stat.getZoneId(), Map.of());
            int[] in = {stat.getInN(), stat.getInE(), stat.getInS(), stat.getInW()};
            int[] out = {stat.getOutN(), stat.getOutE(), stat.getOutS(), stat.getOutW()};
            for (ZoneDirection direction : ZoneDirection.values()) {
                if (targetZoneIds.contains(neighbors.get(direction))) {
                    in[direction.ordinal()] = 0;
                    out[direction.ordinal()] = 0;
                }
            }
            add(inByDirection, in[0], in[1], in[2], in[3]);
            add(outByDirection, out[0], out[1], out[2], out[3]);
        }
        int level = levelService.judgeLevel(target.levelTarget(), density).getLevel();
        return new DashboardSummaryResponse(
                sum.statAt().atOffset(KST_OFFSET),
                sum.countAvg(),
                density,
                level,
                total(inByDirection),
                total(outByDirection),
                mainDirection(outByDirection),
                0,
                cameraCount,
                runningCameraCount,
                todayMaxCount);
    }

    // 합산에 들어간 구역들의 방향별 이웃(구역 번호 → 방향 → 이웃 구역 번호)
    private Map<Long, Map<ZoneDirection, Long>> findNeighbors(List<ZoneStatMinute> stats) {
        List<Long> zoneIds = stats.stream().map(ZoneStatMinute::getZoneId).toList();
        Map<Long, Map<ZoneDirection, Long>> result = new HashMap<>();
        for (ZoneNeighbor row : zoneNeighborRepository.findByZoneIdIn(zoneIds)) {
            result.computeIfAbsent(row.getZoneId(), id -> new EnumMap<>(ZoneDirection.class))
                    .put(row.getDirection(), row.getNeighborId());
        }
        return result;
    }

    private void add(int[] byDirection, int north, int east, int south, int west) {
        byDirection[0] += north;
        byDirection[1] += east;
        byDirection[2] += south;
        byDirection[3] += west;
    }

    private int total(int[] byDirection) {
        int total = 0;
        for (int count : byDirection) {
            total += count;
        }
        return total;
    }

    // 유출이 가장 많은 방향, 같으면 N·E·S·W 순서의 앞쪽. 유출이 하나도 없으면 방향이 없다(null)
    private String mainDirection(int[] outByDirection) {
        int best = 0;
        for (int i = 1; i < outByDirection.length; i++) {
            if (outByDirection[i] > outByDirection[best]) {
                best = i;
            }
        }
        return outByDirection[best] == 0 ? null : DIRECTIONS.get(best);
    }

    /**
     * @param activeZones 현재 값·캠 수에 쓰는 삭제되지 않은 구역
     * @param allZoneIds 오늘 기록에 쓰는 구역 id(삭제 포함)
     */
    private record Target(LevelTarget levelTarget, List<Zone> activeZones, List<Long> allZoneIds, boolean isZone) {}
}
