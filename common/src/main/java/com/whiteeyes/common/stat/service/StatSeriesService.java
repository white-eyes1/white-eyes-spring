package com.whiteeyes.common.stat.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.whiteeyes.common.level.service.LevelService;
import com.whiteeyes.common.level.service.LevelTarget;
import com.whiteeyes.common.region.entity.Region;
import com.whiteeyes.common.region.entity.Zone;
import com.whiteeyes.common.region.entity.ZoneDirection;
import com.whiteeyes.common.region.entity.ZoneNeighbor;
import com.whiteeyes.common.region.repository.ZoneNeighborRepository;
import com.whiteeyes.common.region.service.RegionQueryService;
import com.whiteeyes.common.stat.dto.StatSeriesPoint;
import com.whiteeyes.common.stat.dto.StatSeriesRequest;
import com.whiteeyes.common.stat.dto.StatSeriesResponse;
import com.whiteeyes.common.stat.dto.StatTargetType;
import com.whiteeyes.common.stat.entity.ZoneStatMinute;
import com.whiteeyes.common.stat.repository.ZoneStatMinuteRepository;

/**
 * 통계 그래프 조회(FNC-C-012). 구역 또는 지역의 통계를 단위별 시계열로 돌려준다.
 *
 * <p>지역은 소속 구역(삭제된 구역 포함)을 같은 시각끼리 더한다. 각 점에서 받은 초가 있는 구역만 인원·면적에 넣고
 * (conventions.md '혼잡 단계 판정'과 같은 규칙), 지역 안 구역끼리 주고받은 유입·유출은 뺀다(stats-008).
 */
@Service
public class StatSeriesService {

    private static final ZoneOffset KST_OFFSET = ZoneOffset.ofHours(9);
    // zone_stat_minute.density와 같은 자릿수·반올림(DECIMAL 8,4)
    private static final int DENSITY_SCALE = 4;
    // 평균 체류 시간은 소수 첫째 자리까지(api/public.md 예시 312.5)
    private static final int DWELL_SCALE = 1;

    private final RegionQueryService regionQueryService;
    private final ZoneStatMinuteRepository zoneStatMinuteRepository;
    private final ZoneNeighborRepository zoneNeighborRepository;
    private final LevelService levelService;

    public StatSeriesService(
            RegionQueryService regionQueryService,
            ZoneStatMinuteRepository zoneStatMinuteRepository,
            ZoneNeighborRepository zoneNeighborRepository,
            LevelService levelService) {
        this.regionQueryService = regionQueryService;
        this.zoneStatMinuteRepository = zoneStatMinuteRepository;
        this.zoneNeighborRepository = zoneNeighborRepository;
        this.levelService = levelService;
    }

    /**
     * @throws com.whiteeyes.common.region.exception.RegionNotFoundException 없는 지역이면(404)
     * @throws com.whiteeyes.common.region.exception.ZoneNotFoundException 없거나 삭제된 구역이면(404)
     */
    @Transactional(readOnly = true)
    public StatSeriesResponse findSeries(StatSeriesRequest request) {
        Target target = request.targetType() == StatTargetType.ZONE
                ? zoneTarget(request.targetId())
                : regionTarget(request.targetId());
        // from이 구간 중간이면 그 구간의 시작부터 센다(#25에서 정함)
        LocalDateTime start = request.interval().floor(request.from());
        Map<LocalDateTime, List<ZoneStatMinute>> rowsByAt = findSampledRows(target, start, request.to());
        Map<Long, Map<ZoneDirection, Long>> neighborsByZone = target.isZone() ? Map.of() : findNeighbors(target);

        List<StatSeriesPoint> items = new ArrayList<>();
        for (LocalDateTime at = start; at.isBefore(request.to()); at = request.interval().next(at)) {
            List<ZoneStatMinute> rows = rowsByAt.get(at);
            items.add(rows == null
                    ? StatSeriesPoint.empty(at.atOffset(KST_OFFSET))
                    : toPoint(target, at, rows, neighborsByZone));
        }
        return new StatSeriesResponse(items);
    }

    private Target zoneTarget(long zoneId) {
        Zone zone = regionQueryService.getActiveZone(zoneId);
        return new Target(LevelTarget.zone(zoneId), Map.of(zoneId, zone.getAreaM2()), true);
    }

    // 지난 통계를 보므로 삭제된 구역도 넣는다(FNC-C-012 처리 순서 4). 기간에 통계가 없는 구역은 결과에 영향이 없다
    private Target regionTarget(long regionId) {
        Region region = regionQueryService.getRegion(regionId);
        List<Long> dongIds = regionQueryService.findEmdsUnder(region).stream().map(Region::getId).toList();
        Map<Long, BigDecimal> areaByZone = dongIds.isEmpty()
                ? Map.of()
                : regionQueryService.findAllZonesInDongs(dongIds).stream()
                        .collect(Collectors.toMap(Zone::getId, Zone::getAreaM2));
        return new Target(LevelTarget.region(regionId), areaByZone, false);
    }

    // 받은 초가 0인 행은 점을 비우므로 처음부터 뺀다(FNC-C-012 업무 규칙)
    private Map<LocalDateTime, List<ZoneStatMinute>> findSampledRows(
            Target target, LocalDateTime from, LocalDateTime to) {
        if (target.areaByZone().isEmpty()) {
            return Map.of();
        }
        return zoneStatMinuteRepository
                .findByZoneIdInAndStatAtGreaterThanEqualAndStatAtLessThan(target.areaByZone().keySet(), from, to)
                .stream()
                .filter(row -> row.getSampleSec() > 0)
                .collect(Collectors.groupingBy(ZoneStatMinute::getStatAt));
    }

    private StatSeriesPoint toPoint(
            Target target, LocalDateTime at, List<ZoneStatMinute> rows, Map<Long, Map<ZoneDirection, Long>> neighbors) {
        BigDecimal countAvg = rows.stream().map(ZoneStatMinute::getCountAvg).reduce(BigDecimal.ZERO, BigDecimal::add);
        // 구역 하나면 저장된 밀집도(당시 면적 기준)를 그대로 쓴다. 지역은 합산한 구역의 현재 면적 합으로 나눈다
        BigDecimal density = target.isZone()
                ? rows.get(0).getDensity()
                : countAvg.divide(sumArea(target, rows), DENSITY_SCALE, RoundingMode.HALF_UP);
        long dwellSumSec = rows.stream().mapToLong(ZoneStatMinute::getDwellSumSec).sum();
        long dwellCount = rows.stream().mapToLong(ZoneStatMinute::getDwellCount).sum();
        BigDecimal dwellAvgSec = dwellCount == 0
                ? null
                : BigDecimal.valueOf(dwellSumSec).divide(BigDecimal.valueOf(dwellCount), DWELL_SCALE, RoundingMode.HALF_UP);
        return new StatSeriesPoint(
                at.atOffset(KST_OFFSET),
                countAvg,
                rows.stream().mapToInt(ZoneStatMinute::getCountMax).sum(),
                rows.stream().mapToInt(ZoneStatMinute::getCountMin).sum(),
                density,
                levelService.judgeLevel(target.levelTarget(), density).getLevel(),
                sumFlow(target, rows, neighbors, true),
                sumFlow(target, rows, neighbors, false),
                dwellAvgSec,
                rows.stream().mapToInt(ZoneStatMinute::getSampleSec).max().orElse(0),
                // 1분 통계에는 시연용 데이터가 없다(tables.md zone_stat_minute)
                false);
    }

    private BigDecimal sumArea(Target target, List<ZoneStatMinute> rows) {
        return rows.stream()
                .map(row -> target.areaByZone().get(row.getZoneId()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    // 방향 d의 이웃이 지역 안 구역이면 그 방향의 유입·유출은 지역 안 이동이라 세지 않는다(FNC-C-012 업무 규칙)
    private Map<String, Integer> sumFlow(
            Target target, List<ZoneStatMinute> rows, Map<Long, Map<ZoneDirection, Long>> neighbors, boolean inflow) {
        Set<Long> targetZoneIds = target.areaByZone().keySet();
        Map<String, Integer> result = new LinkedHashMap<>();
        for (ZoneDirection direction : ZoneDirection.values()) {
            int sum = 0;
            for (ZoneStatMinute row : rows) {
                Long neighborId = neighbors.getOrDefault(row.getZoneId(), Map.of()).get(direction);
                if (neighborId == null || !targetZoneIds.contains(neighborId)) {
                    sum += inflow ? inflowOf(row, direction) : outflowOf(row, direction);
                }
            }
            result.put(direction.name(), sum);
        }
        return result;
    }

    private static int inflowOf(ZoneStatMinute row, ZoneDirection direction) {
        return switch (direction) {
            case N -> row.getInN();
            case E -> row.getInE();
            case S -> row.getInS();
            case W -> row.getInW();
        };
    }

    private static int outflowOf(ZoneStatMinute row, ZoneDirection direction) {
        return switch (direction) {
            case N -> row.getOutN();
            case E -> row.getOutE();
            case S -> row.getOutS();
            case W -> row.getOutW();
        };
    }

    // 지역 구역들의 방향별 이웃(구역 번호 → 방향 → 이웃 구역 번호). 지금의 zone_neighbor를 쓴다(#25에서 정함)
    private Map<Long, Map<ZoneDirection, Long>> findNeighbors(Target target) {
        if (target.areaByZone().isEmpty()) {
            return Map.of();
        }
        Map<Long, Map<ZoneDirection, Long>> result = new HashMap<>();
        for (ZoneNeighbor row : zoneNeighborRepository.findByZoneIdIn(target.areaByZone().keySet())) {
            result.computeIfAbsent(row.getZoneId(), zoneId -> new HashMap<>())
                    .put(row.getDirection(), row.getNeighborId());
        }
        return result;
    }

    /**
     * @param areaByZone 합산 대상 구역 번호 → 현재 면적(㎡). 구역 대상이면 그 구역 하나
     */
    private record Target(LevelTarget levelTarget, Map<Long, BigDecimal> areaByZone, boolean isZone) {
    }
}
