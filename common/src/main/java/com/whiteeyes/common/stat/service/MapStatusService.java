package com.whiteeyes.common.stat.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.whiteeyes.common.event.service.OngoingEventService;
import com.whiteeyes.common.event.service.TodayEventCountService;
import com.whiteeyes.common.level.service.LevelService;
import com.whiteeyes.common.level.service.LevelTarget;
import com.whiteeyes.common.region.entity.Region;
import com.whiteeyes.common.region.entity.RegionLevel;
import com.whiteeyes.common.region.entity.Zone;
import com.whiteeyes.common.region.service.RegionQueryService;
import com.whiteeyes.common.stat.dto.MapStatusItem;
import com.whiteeyes.common.stat.dto.MapStatusResponse;
import com.whiteeyes.common.stat.dto.MapUnit;
import com.whiteeyes.common.stat.entity.ZoneNowCount;
import com.whiteeyes.common.stat.entity.ZoneStatMinute;
import com.whiteeyes.common.stat.service.RecentZoneStatReader.LatestMinuteSum;

/**
 * 지도 혼잡 단계 색 표시(FNC-C-009). 대상 지역의 하위 단위별 최근 1분 통계와 혼잡 단계를 돌려준다.
 */
@Service
public class MapStatusService {

    private static final ZoneOffset KST_OFFSET = ZoneOffset.ofHours(9);
    private static final String ZONE_UNIT_TYPE = "ZONE";

    private final RegionQueryService regionQueryService;
    private final RecentZoneStatReader recentZoneStatReader;
    private final LevelService levelService;
    private final TodayEventCountService todayEventCountService;
    private final OngoingEventService ongoingEventService;
    private final NowCountReader nowCountReader;

    public MapStatusService(
            RegionQueryService regionQueryService,
            RecentZoneStatReader recentZoneStatReader,
            LevelService levelService,
            TodayEventCountService todayEventCountService,
            OngoingEventService ongoingEventService,
            NowCountReader nowCountReader) {
        this.regionQueryService = regionQueryService;
        this.recentZoneStatReader = recentZoneStatReader;
        this.levelService = levelService;
        this.todayEventCountService = todayEventCountService;
        this.ongoingEventService = ongoingEventService;
        this.nowCountReader = nowCountReader;
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
        // 5초마다 갱신되는 현재 인원(FNC-A-054). 1분 통계와 따로 읽는다
        Map<Long, ZoneNowCount> nowCountByZone =
                nowCountReader.findFresh(zones.stream().map(Zone::getId).toList(), now);
        // 오늘 이벤트 수는 삭제된 구역의 것까지 센다(FNC-C-017)
        List<Zone> allZones = emdCodeById.isEmpty()
                ? List.of()
                : regionQueryService.findAllZonesInDongs(emdCodeById.keySet());
        Map<Long, Integer> eventCountByZone = todayEventCountService.countByZone(
                allZones.stream().map(Zone::getId).toList(), now.toLocalDate().atStartOfDay());
        // 진행 중 이벤트는 지도에 보이는(삭제되지 않은) 구역만 본다(FNC-C-009 처리 순서 5)
        Set<Long> ongoingZoneIds =
                ongoingEventService.findZoneIdsWithOngoingEvent(zones.stream().map(Zone::getId).toList());

        if (unit == MapUnit.ZONE || region.getLevel() == RegionLevel.EMD) {
            // 구역 핀 툴팁의 구·동 이름(SCR-C-003)
            Map<Long, String> regionPathByDong = zones.isEmpty()
                    ? Map.of()
                    : regionQueryService.findPaths(zones.stream().map(Zone::getDongId).collect(Collectors.toSet()));
            List<MapStatusItem> items = zones.stream()
                    .map(zone -> toZoneItem(
                            zone,
                            regionPathByDong.get(zone.getDongId()),
                            latestStatByZone.get(zone.getId()),
                            nowCountByZone.get(zone.getId()),
                            eventCountByZone,
                            ongoingZoneIds))
                    .toList();
            return new MapStatusResponse(ZONE_UNIT_TYPE, items);
        }
        List<MapStatusItem> items = regionQueryService.findChildRegions(regionId).stream()
                .map(child -> toRegionItem(
                        child,
                        zonesUnder(child, zones, emdCodeById),
                        sumEventCount(zonesUnder(child, allZones, emdCodeById), eventCountByZone),
                        hasOngoingEvent(zonesUnder(child, zones, emdCodeById), ongoingZoneIds),
                        latestStatByZone,
                        sumNowCount(zonesUnder(child, zones, emdCodeById), nowCountByZone)))
                .toList();
        return new MapStatusResponse(childLevelOf(region.getLevel()).name(), items);
    }

    private MapStatusItem toZoneItem(
            Zone zone,
            String regionPath,
            ZoneStatMinute stat,
            ZoneNowCount nowCount,
            Map<Long, Integer> eventCountByZone,
            Set<Long> ongoingZoneIds) {
        int todayEventCount = eventCountByZone.getOrDefault(zone.getId(), 0);
        boolean ongoingEvent = ongoingZoneIds.contains(zone.getId());
        Integer currentCount = nowCount == null ? null : nowCount.getPersonCount();
        if (stat == null) {
            return noDataItem(
                    zone.getId(),
                    zone.getName(),
                    regionPath,
                    zone.getCenterLat(),
                    zone.getCenterLng(),
                    currentCount,
                    todayEventCount,
                    ongoingEvent);
        }
        int level = levelService
                .judgeLevel(LevelTarget.zone(zone.getId()), stat.getDensity())
                .getLevel();
        return new MapStatusItem(
                zone.getId(),
                zone.getName(),
                regionPath,
                zone.getCenterLat(),
                zone.getCenterLng(),
                stat.getCountAvg(),
                currentCount,
                stat.getDensity(),
                level,
                stat.getStatAt().atOffset(KST_OFFSET),
                todayEventCount,
                ongoingEvent);
    }

    private MapStatusItem toRegionItem(
            Region child,
            List<Zone> zones,
            int todayEventCount,
            boolean ongoingEvent,
            Map<Long, ZoneStatMinute> latestStatByZone,
            Integer currentCount) {
        return recentZoneStatReader
                .sumAtLatestMinute(zones, latestStatByZone)
                .map(sum -> toRegionItem(child, sum, currentCount, todayEventCount, ongoingEvent))
                .orElseGet(
                        () -> noDataItem(
                                child.getId(),
                                child.getName(),
                                null,
                                null,
                                null,
                                currentCount,
                                todayEventCount,
                                ongoingEvent));
    }

    private MapStatusItem toRegionItem(
            Region child, LatestMinuteSum sum, Integer currentCount, int todayEventCount, boolean ongoingEvent) {
        int level = levelService
                .judgeLevel(LevelTarget.region(child.getId()), sum.density())
                .getLevel();
        return new MapStatusItem(
                child.getId(),
                child.getName(),
                null,
                null,
                null,
                sum.countAvg(),
                currentCount,
                sum.density(),
                level,
                sum.statAt().atOffset(KST_OFFSET),
                todayEventCount,
                ongoingEvent);
    }

    private MapStatusItem noDataItem(
            long id,
            String name,
            String regionPath,
            BigDecimal centerLat,
            BigDecimal centerLng,
            Integer currentCount,
            int todayEventCount,
            boolean ongoingEvent) {
        return new MapStatusItem(
                id, name, regionPath, centerLat, centerLng, null, currentCount, null, null, null,
                todayEventCount, ongoingEvent);
    }

    // 신선한 현재 인원이 있는 구역만 더한다. 하나도 없으면 null(화면은 1분 평균으로 되돌린다)
    private Integer sumNowCount(List<Zone> zones, Map<Long, ZoneNowCount> nowCountByZone) {
        List<ZoneNowCount> found = zones.stream()
                .map(zone -> nowCountByZone.get(zone.getId()))
                .filter(java.util.Objects::nonNull)
                .toList();
        return found.isEmpty() ? null : found.stream().mapToInt(ZoneNowCount::getPersonCount).sum();
    }

    private boolean hasOngoingEvent(List<Zone> zones, Set<Long> ongoingZoneIds) {
        return zones.stream().anyMatch(zone -> ongoingZoneIds.contains(zone.getId()));
    }

    private int sumEventCount(List<Zone> zones, Map<Long, Integer> eventCountByZone) {
        return zones.stream().mapToInt(zone -> eventCountByZone.getOrDefault(zone.getId(), 0)).sum();
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
