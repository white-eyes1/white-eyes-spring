package com.whiteeyes.common.stat.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.whiteeyes.common.level.service.LevelService;
import com.whiteeyes.common.level.service.LevelTarget;
import com.whiteeyes.common.region.entity.Region;
import com.whiteeyes.common.region.entity.RegionLevel;
import com.whiteeyes.common.region.entity.Zone;
import com.whiteeyes.common.region.service.RegionQueryService;
import com.whiteeyes.common.stat.dto.MapStatusItem;
import com.whiteeyes.common.stat.dto.MapStatusResponse;
import com.whiteeyes.common.stat.dto.MapUnit;
import com.whiteeyes.common.stat.entity.ZoneStatMinute;
import com.whiteeyes.common.stat.service.RecentZoneStatReader.LatestMinuteSum;

/**
 * 지도 혼잡 단계 색 표시(FNC-C-009). 대상 지역의 하위 단위별 최근 1분 통계와 혼잡 단계를 돌려준다.
 */
// TODO(FNC-A-033·034): 2단계에서 단위별 오늘 혼잡·급증 이벤트 수와 진행 중 여부를 채운다. 1단계는 0·false(이슈 #20 범위 밖)
@Service
public class MapStatusService {

    private static final ZoneOffset KST_OFFSET = ZoneOffset.ofHours(9);
    private static final String ZONE_UNIT_TYPE = "ZONE";

    private final RegionQueryService regionQueryService;
    private final RecentZoneStatReader recentZoneStatReader;
    private final LevelService levelService;

    public MapStatusService(
            RegionQueryService regionQueryService,
            RecentZoneStatReader recentZoneStatReader,
            LevelService levelService) {
        this.regionQueryService = regionQueryService;
        this.recentZoneStatReader = recentZoneStatReader;
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
                : regionQueryService.findZonesInDongs(emdCodeById.keySet()).stream()
                        .sorted(Comparator.comparing(Zone::getId))
                        .toList();
        Map<Long, ZoneStatMinute> latestStatByZone = recentZoneStatReader.findLatestStats(zones, now);

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

    private MapStatusItem toRegionItem(Region child, List<Zone> zones, Map<Long, ZoneStatMinute> latestStatByZone) {
        return recentZoneStatReader
                .sumAtLatestMinute(zones, latestStatByZone)
                .map(sum -> toRegionItem(child, sum))
                .orElseGet(() -> noDataItem(child.getId(), child.getName(), null, null));
    }

    private MapStatusItem toRegionItem(Region child, LatestMinuteSum sum) {
        int level = levelService
                .judgeLevel(LevelTarget.region(child.getId()), sum.density())
                .getLevel();
        return new MapStatusItem(
                child.getId(),
                child.getName(),
                null,
                null,
                sum.countAvg(),
                sum.density(),
                level,
                sum.statAt().atOffset(KST_OFFSET),
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
