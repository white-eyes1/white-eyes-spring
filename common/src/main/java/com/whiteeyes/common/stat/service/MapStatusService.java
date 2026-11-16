package com.whiteeyes.common.stat.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
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
import com.whiteeyes.common.stat.entity.ZoneStatMinute;
import com.whiteeyes.common.stat.service.NowCountSource.NowCount;
import com.whiteeyes.common.stat.service.RecentZoneStatReader.LatestMinuteSum;

/**
 * 지도 혼잡 단계 색 표시(FNC-C-009). 대상 지역의 하위 단위별 최근 1분 통계와 혼잡 단계를 돌려준다.
 */
@Service
@RequiredArgsConstructor
public class MapStatusService {

    // 응답의 통계 시각에 붙이는 한국 시간 오프셋(+09:00)
    private static final ZoneOffset KST_OFFSET = ZoneOffset.ofHours(9);
    // 구역 단위 응답일 때 unitType에 넣는 값
    private static final String ZONE_UNIT_TYPE = "ZONE";

    // 지역 → 읍·면·동 → 구역 목록을 찾는다
    private final RegionQueryService regionQueryService;
    // 최근 5분 안의 가장 늦은 1분 통계와 합산을 읽는다
    private final RecentZoneStatReader recentZoneStatReader;
    // 밀집도를 혼잡 단계(1~3)로 바꾼다
    private final LevelService levelService;
    // 구역별 오늘 이벤트 수를 센다
    private final TodayEventCountService todayEventCountService;
    // 지금 진행 중인 이벤트가 있는 구역을 찾는다
    private final OngoingEventService ongoingEventService;
    // 관리자 앱에만 있다. 사용자 앱은 빈이 없어 currentCount가 null이다(결정 0018)
    private final ObjectProvider<NowCountSource> nowCountSource;

    /**
     * @param now KST 현재 시각
     * @throws com.whiteeyes.common.region.exception.RegionNotFoundException 없는 지역이면(404)
     */
    @Transactional(readOnly = true)
    public MapStatusResponse findMapStatus(long regionId, MapUnit unit, LocalDateTime now) {
        // 대상 지역을 찾는다. 없으면 404
        Region region = regionQueryService.getRegion(regionId);
        // 지역 아래 읍·면·동 번호 → 지역 코드. 코드는 나중에 하위 지역에 어떤 읍·면·동이 속하는지 가르는 데 쓴다
        Map<Long, String> emdCodeById = regionQueryService.findEmdsUnder(region).stream()
                .collect(Collectors.toMap(Region::getId, Region::getCode));
        // 삭제되지 않은 구역(지도에 그려지는 구역)을 번호 순으로 읽는다. 읍·면·동이 없으면 조회하지 않는다
        List<Zone> zones = emdCodeById.isEmpty()
                ? List.of()
                : regionQueryService.findZonesInDongs(emdCodeById.keySet()).stream()
                        .sorted(Comparator.comparing(Zone::getId))
                        .toList();
        // 구역별 최근 5분 안 가장 늦은 1분 통계. 없는 구역은 빠진다
        Map<Long, ZoneStatMinute> latestStatByZone = recentZoneStatReader.findLatestStats(zones, now);
        // 5초마다 갱신되는 현재 인원(FNC-A-054). 1분 통계와 따로 읽는다
        NowCountSource nowCounts = nowCountSource.getIfAvailable();
        Map<Long, NowCount> nowCountByZone = nowCounts == null
                ? Map.of()
                : nowCounts.findFresh(zones.stream().map(Zone::getId).toList(), now);
        // 오늘 이벤트 수는 삭제된 구역의 것까지 센다(FNC-C-017)
        // 삭제된 구역까지 포함한 구역 목록(오늘 이벤트 수 합산용)
        List<Zone> allZones =
                emdCodeById.isEmpty() ? List.of() : regionQueryService.findAllZonesInDongs(emdCodeById.keySet());
        Map<Long, Integer> eventCountByZone = todayEventCountService.countByZone(
                allZones.stream().map(Zone::getId).toList(), now.toLocalDate().atStartOfDay());
        // 진행 중 이벤트는 지도에 보이는(삭제되지 않은) 구역만 본다(FNC-C-009 처리 순서 5)
        Set<Long> ongoingZoneIds = ongoingEventService.findZoneIdsWithOngoingEvent(
                zones.stream().map(Zone::getId).toList());

        // 구역 단위로 달라고 했거나, 읍·면·동이라 더 내려갈 하위 지역이 없으면 구역을 항목으로 내려보낸다
        if (unit == MapUnit.ZONE || region.getLevel() == RegionLevel.EMD) {
            // 구역 핀 툴팁의 구·동 이름(SCR-C-003)
            // 구역 핀 툴팁에 쓸 '시·군·구 · 읍·면·동' 이름. 읍·면·동 번호를 한 번에 넘겨 쿼리 한 번으로 읽는다
            Map<Long, String> regionPathByDong = zones.isEmpty()
                    ? Map.of()
                    : regionQueryService.findPaths(
                            zones.stream().map(Zone::getDongId).collect(Collectors.toSet()));
            List<MapStatusItem> items = zones.stream()
                    // 구역마다 항목 하나로 바꾼다. 통계가 없는 구역도 항목은 만들고 값만 비운다(지도에서 회색 처리)
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
        // 그 외에는 하위 지역마다 항목 하나. 하위 지역 아래 구역들(삭제 안 된 것/삭제 포함)을 코드 접두사로 골라 합산한다
        List<MapStatusItem> items = regionQueryService.findChildRegions(regionId).stream()
                .map(child -> toRegionItem(
                        child,
                        zonesUnder(child, zones, emdCodeById),
                        sumEventCount(zonesUnder(child, allZones, emdCodeById), eventCountByZone),
                        hasOngoingEvent(zonesUnder(child, zones, emdCodeById), ongoingZoneIds),
                        latestStatByZone,
                        sumNowCount(zonesUnder(child, zones, emdCodeById), nowCountByZone)))
                .toList();
        // unitType에 하위 지역의 단계 이름(SIGUNGU 또는 EMD)을 넣어 화면이 항목의 종류를 알게 한다
        return new MapStatusResponse(childLevelOf(region.getLevel()).name(), items);
    }

    private MapStatusItem toZoneItem(
            Zone zone,
            String regionPath,
            ZoneStatMinute stat,
            NowCount nowCount,
            Map<Long, Integer> eventCountByZone,
            Set<Long> ongoingZoneIds) {
        // 이벤트가 없는 구역은 맵에 키가 없으므로 0으로 본다
        int todayEventCount = eventCountByZone.getOrDefault(zone.getId(), 0);
        boolean ongoingEvent = ongoingZoneIds.contains(zone.getId());
        // 신선한 현재 인원이 없으면 null(화면이 1분 평균으로 되돌린다)
        Integer currentCount = nowCount == null ? null : nowCount.personCount();
        // 최근 5분 안에 통계가 없는 구역: 통계 값은 비우고, 구역 정보·현재 인원·이벤트 정보만 채운다
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
        // 구역의 저장된 밀집도를 구역 기준값으로 혼잡 단계(1~3)로 바꾼다
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

    /** 하위 지역 하나의 항목을 만든다. 소속 구역들의 가장 늦은 1분 통계를 합산하고, 없으면 값을 비운 항목을 돌려준다 */
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
                .orElseGet(() -> noDataItem(
                        child.getId(), child.getName(), null, null, null, currentCount, todayEventCount, ongoingEvent));
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

    /** 통계가 없는 단위의 항목. 이름·위치·현재 인원·이벤트 정보만 채우고 통계 값과 단계는 null이다 */
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
                id,
                name,
                regionPath,
                centerLat,
                centerLng,
                null,
                currentCount,
                null,
                null,
                null,
                todayEventCount,
                ongoingEvent);
    }

    // 신선한 현재 인원이 있는 구역만 더한다. 하나도 없으면 null(화면은 1분 평균으로 되돌린다)
    /** 구역 목록의 신선한 현재 인원 합(하위 지역 항목의 currentCount) */
    private Integer sumNowCount(List<Zone> zones, Map<Long, NowCount> nowCountByZone) {
        List<NowCount> found = zones.stream()
                .map(zone -> nowCountByZone.get(zone.getId()))
                .filter(java.util.Objects::nonNull)
                .toList();
        return found.isEmpty()
                ? null
                : found.stream().mapToInt(NowCount::personCount).sum();
    }

    /** 구역 중 하나라도 진행 중인 이벤트가 있으면 true */
    private boolean hasOngoingEvent(List<Zone> zones, Set<Long> ongoingZoneIds) {
        return zones.stream().anyMatch(zone -> ongoingZoneIds.contains(zone.getId()));
    }

    /** 구역들의 오늘 이벤트 수 합 */
    private int sumEventCount(List<Zone> zones, Map<Long, Integer> eventCountByZone) {
        return zones.stream()
                .mapToInt(zone -> eventCountByZone.getOrDefault(zone.getId(), 0))
                .sum();
    }

    // 하위 지역 코드가 구역 소속 읍·면·동 코드의 앞자리면 그 하위 지역 아래의 구역이다(decisions/0014)
    private List<Zone> zonesUnder(Region child, List<Zone> zones, Map<Long, String> emdCodeById) {
        return zones.stream()
                // 읍·면·동 코드가 하위 지역 코드로 시작하면 그 하위 지역에 속한다(코드가 계층형이라 접두사로 소속을 알 수 있다)
                .filter(zone -> emdCodeById.get(zone.getDongId()).startsWith(child.getCode()))
                .toList();
    }

    /** 한 단계 아래 지역 단계. 시·도 아래는 시·군·구, 시·군·구 아래는 읍·면·동이다 */
    private RegionLevel childLevelOf(RegionLevel level) {
        return switch (level) {
            case SIDO -> RegionLevel.SIGUNGU;
            case SIGUNGU, EMD -> RegionLevel.EMD;
        };
    }
}
