package com.whiteeyes.common.stat.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.whiteeyes.common.level.service.LevelService;
import com.whiteeyes.common.level.service.LevelTarget;
import com.whiteeyes.common.region.entity.Region;
import com.whiteeyes.common.region.entity.RegionLevel;
import com.whiteeyes.common.region.service.RegionQueryService;
import com.whiteeyes.common.stat.dto.MapStatusItem;
import com.whiteeyes.common.stat.dto.MapStatusResponse;
import com.whiteeyes.common.stat.dto.MapUnit;
import com.whiteeyes.common.stat.entity.ZoneStatMinute;
import com.whiteeyes.common.stat.repository.ZoneStatMinuteRepository;
import com.whiteeyes.common.zone.entity.Zone;
import com.whiteeyes.common.zone.service.ZoneLookupService;

/**
 * 지도 혼잡 단계 색 표시(FNC-C-009). 대상 지역의 하위 단위별 최근 1분 통계와 혼잡 단계를 돌려준다.
 */
// TODO(FNC-A-033·034): 2단계에서 단위별 오늘 혼잡·급증 이벤트 수와 진행 중 여부를 채운다. 1단계는 0·false(이슈 #20 범위 밖)
@Service
public class MapStatusService {

    private static final ZoneOffset KST_OFFSET = ZoneOffset.ofHours(9);
    // 이 시간 안에 통계가 없으면 '데이터 없음'(FNC-C-009 업무 규칙)
    private static final Duration RECENT_WINDOW = Duration.ofMinutes(5);
    // zone_stat_minute.density와 같은 자릿수·반올림(DECIMAL 8,4, FNC-A-028)
    private static final int DENSITY_SCALE = 4;
    private static final String ZONE_UNIT_TYPE = "ZONE";

    private final RegionQueryService regionQueryService;
    private final ZoneLookupService zoneLookupService;
    private final ZoneStatMinuteRepository zoneStatMinuteRepository;
    private final LevelService levelService;

    public MapStatusService(
            RegionQueryService regionQueryService,
            ZoneLookupService zoneLookupService,
            ZoneStatMinuteRepository zoneStatMinuteRepository,
            LevelService levelService) {
        this.regionQueryService = regionQueryService;
        this.zoneLookupService = zoneLookupService;
        this.zoneStatMinuteRepository = zoneStatMinuteRepository;
        this.levelService = levelService;
    }

    /**
     * @param now KST 현재 시각
     * @throws com.whiteeyes.common.region.exception.RegionNotFoundException 없는 지역이면(404)
     */
    @Transactional(readOnly = true)
    public MapStatusResponse findMapStatus(long regionId, MapUnit unit, LocalDateTime now) {
        Region region = regionQueryService.getRegion(regionId);
        Map<Long, String> emdCodeById = regionQueryService.findEmdsUnder(region).stream()
                .collect(Collectors.toMap(Region::getId, Region::getCode));
        List<Zone> zones = emdCodeById.isEmpty()
                ? List.of()
                : zoneLookupService.findZonesInDongs(emdCodeById.keySet()).stream()
                        .sorted(Comparator.comparing(Zone::getId))
                        .toList();
        Map<Long, ZoneStatMinute> latestStatByZone = findLatestStats(zones, now);

        if (unit == MapUnit.ZONE || region.getLevel() == RegionLevel.EMD) {
            List<MapStatusItem> items = zones.stream()
                    .map(zone -> toZoneItem(zone, latestStatByZone.get(zone.getId())))
                    .toList();
            return new MapStatusResponse(ZONE_UNIT_TYPE, items);
        }
        List<MapStatusItem> items = regionQueryService.findChildRegions(regionId).stream()
                .map(child -> toRegionItem(child, zonesUnder(child, zones, emdCodeById), latestStatByZone))
                .toList();
        return new MapStatusResponse(childLevelOf(region.getLevel()).name(), items);
    }

    // 구역별로 최근 5분 안의 가장 늦은 1분 통계
    private Map<Long, ZoneStatMinute> findLatestStats(List<Zone> zones, LocalDateTime now) {
        if (zones.isEmpty()) {
            return Map.of();
        }
        List<Long> zoneIds = zones.stream().map(Zone::getId).toList();
        return zoneStatMinuteRepository
                .findByZoneIdInAndStatAtGreaterThanEqual(zoneIds, now.minus(RECENT_WINDOW))
                .stream()
                .collect(Collectors.toMap(
                        ZoneStatMinute::getZoneId,
                        Function.identity(),
                        (left, right) -> left.getStatAt().isAfter(right.getStatAt()) ? left : right));
    }

    private MapStatusItem toZoneItem(Zone zone, ZoneStatMinute stat) {
        if (stat == null) {
            return noDataItem(zone.getId(), zone.getName(), zone.getCenterLat(), zone.getCenterLng());
        }
        int level = levelService
                .judgeLevel(LevelTarget.zone(zone.getId()), stat.getDensity())
                .getLevel();
        return new MapStatusItem(
                zone.getId(),
                zone.getName(),
                zone.getCenterLat(),
                zone.getCenterLng(),
                stat.getCountAvg(),
                stat.getDensity(),
                level,
                stat.getStatAt().atOffset(KST_OFFSET),
                0,
                false);
    }

    // 소속 구역 중 가장 늦은 1분(T)의 행만 더한다. 밀집도 = count_avg 합 ÷ 그 구역들의 현재 면적 합(conventions.md '혼잡 단계 판정')
    private MapStatusItem toRegionItem(Region child, List<Zone> zones, Map<Long, ZoneStatMinute> latestStatByZone) {
        Optional<LocalDateTime> latestMinute = zones.stream()
                .map(zone -> latestStatByZone.get(zone.getId()))
                .filter(Objects::nonNull)
                .map(ZoneStatMinute::getStatAt)
                .max(Comparator.naturalOrder());
        if (latestMinute.isEmpty()) {
            return noDataItem(child.getId(), child.getName(), null, null);
        }
        List<Zone> zonesAtLatest = zones.stream()
                .filter(zone -> {
                    ZoneStatMinute stat = latestStatByZone.get(zone.getId());
                    return stat != null && stat.getStatAt().equals(latestMinute.get());
                })
                .toList();
        BigDecimal countAvg = zonesAtLatest.stream()
                .map(zone -> latestStatByZone.get(zone.getId()).getCountAvg())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal area = zonesAtLatest.stream().map(Zone::getAreaM2).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal density = countAvg.divide(area, DENSITY_SCALE, RoundingMode.HALF_UP);
        int level = levelService
                .judgeLevel(LevelTarget.region(child.getId()), density)
                .getLevel();
        return new MapStatusItem(
                child.getId(),
                child.getName(),
                null,
                null,
                countAvg,
                density,
                level,
                latestMinute.get().atOffset(KST_OFFSET),
                0,
                false);
    }

    private MapStatusItem noDataItem(long id, String name, BigDecimal centerLat, BigDecimal centerLng) {
        return new MapStatusItem(id, name, centerLat, centerLng, null, null, null, null, 0, false);
    }

    // 하위 지역 코드가 구역 소속 읍·면·동 코드의 앞자리면 그 하위 지역 아래의 구역이다(decisions/0014)
    private List<Zone> zonesUnder(Region child, List<Zone> zones, Map<Long, String> emdCodeById) {
        return zones.stream()
                .filter(zone -> emdCodeById.get(zone.getDongId()).startsWith(child.getCode()))
                .toList();
    }

    private RegionLevel childLevelOf(RegionLevel level) {
        return switch (level) {
            case SIDO -> RegionLevel.SIGUNGU;
            case SIGUNGU, EMD -> RegionLevel.EMD;
        };
    }
}
