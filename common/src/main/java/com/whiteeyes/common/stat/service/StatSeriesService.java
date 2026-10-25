package com.whiteeyes.common.stat.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.ToIntFunction;
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
import com.whiteeyes.common.stat.dto.StatInterval;
import com.whiteeyes.common.stat.dto.StatSeriesPoint;
import com.whiteeyes.common.stat.dto.StatSeriesRequest;
import com.whiteeyes.common.stat.dto.StatSeriesResponse;
import com.whiteeyes.common.stat.dto.StatTargetType;
import com.whiteeyes.common.stat.entity.StatValues;
import com.whiteeyes.common.stat.entity.ZoneStatDaily;
import com.whiteeyes.common.stat.repository.ZoneStatDailyRepository;
import com.whiteeyes.common.stat.repository.ZoneStatHourlyRepository;
import com.whiteeyes.common.stat.repository.ZoneStatMinuteRepository;

/**
 * 통계 그래프 조회(FNC-C-012). 구역 또는 지역의 통계를 단위별 시계열로 돌려준다.
 * 원본은 단위로 고른다: 1m=zone_stat_minute, 1h=zone_stat_hourly, 1d 이상=zone_stat_daily(처리 순서 2).
 * 1w·1M·1y는 구역별로 일별 통계를 먼저 sample_sec 가중 평균으로 묶은 뒤 지역을 합산한다(처리 순서 3→4, #25에서 정함).
 *
 * <p>지역은 소속 구역(삭제된 구역 포함)을 같은 시각끼리 더한다. 각 점에서 받은 초가 있는 구역만 인원·면적에 넣고
 * (conventions.md '혼잡 단계 판정'과 같은 규칙), 지역 안 구역끼리 주고받은 유입·유출은 뺀다(stats-008).
 */
@Service
public class StatSeriesService {

    private static final ZoneOffset KST_OFFSET = ZoneOffset.ofHours(9);
    // zone_stat_*.density와 같은 자릿수·반올림(DECIMAL 8,4)
    private static final int DENSITY_SCALE = 4;
    // 평균 체류 시간은 소수 첫째 자리까지(api/public.md 예시 312.5)
    private static final int DWELL_SCALE = 1;

    private final RegionQueryService regionQueryService;
    private final ZoneStatMinuteRepository zoneStatMinuteRepository;
    private final ZoneStatHourlyRepository zoneStatHourlyRepository;
    private final ZoneStatDailyRepository zoneStatDailyRepository;
    private final ZoneNeighborRepository zoneNeighborRepository;
    private final LevelService levelService;

    public StatSeriesService(
            RegionQueryService regionQueryService,
            ZoneStatMinuteRepository zoneStatMinuteRepository,
            ZoneStatHourlyRepository zoneStatHourlyRepository,
            ZoneStatDailyRepository zoneStatDailyRepository,
            ZoneNeighborRepository zoneNeighborRepository,
            LevelService levelService) {
        this.regionQueryService = regionQueryService;
        this.zoneStatMinuteRepository = zoneStatMinuteRepository;
        this.zoneStatHourlyRepository = zoneStatHourlyRepository;
        this.zoneStatDailyRepository = zoneStatDailyRepository;
        this.zoneNeighborRepository = zoneNeighborRepository;
        this.levelService = levelService;
    }

    /**
     * @throws com.whiteeyes.common.region.exception.RegionNotFoundException 없는 지역이면(404)
     * @throws com.whiteeyes.common.region.exception.ZoneNotFoundException 없는 구역이면(404). 삭제된 구역은 지난 통계를 보므로 받는다
     */
    @Transactional(readOnly = true)
    public StatSeriesResponse findSeries(StatSeriesRequest request) {
        Target target = request.targetType() == StatTargetType.ZONE
                ? zoneTarget(request.targetId())
                : regionTarget(request.targetId());
        StatInterval interval = request.interval();
        List<LocalDateTime> buckets = buckets(interval, request.from(), request.to());
        LocalDateTime end = interval.next(buckets.get(buckets.size() - 1));
        Map<LocalDateTime, List<ZoneRow>> rowsByAt = findSampledRows(target, interval, buckets.get(0), end);
        Map<Long, Map<ZoneDirection, Long>> neighborsByZone = target.isZone() ? Map.of() : findNeighbors(target);

        List<StatSeriesPoint> items = new ArrayList<>();
        for (LocalDateTime at : buckets) {
            List<ZoneRow> rows = rowsByAt.get(at);
            items.add(rows == null
                    ? StatSeriesPoint.empty(at.atOffset(KST_OFFSET))
                    : toPoint(target, at, rows, neighborsByZone));
        }
        return new StatSeriesResponse(items);
    }

    /**
     * 구역마다 따로 낸 시계열(통계 엑셀의 구역별 행, FNC-C-023). 값은 구역 대상 {@link #findSeries}와 같다.
     * 원본은 구역 수와 관계없이 한 번에 읽는다. 구역 확인(삭제 여부)은 부르는 쪽이 한다.
     *
     * @return 구역 번호 → 점 목록(from이 든 구간부터 to 앞까지). 받은 순서대로
     */
    @Transactional(readOnly = true)
    public Map<Long, List<StatSeriesPoint>> findZoneSeries(
            Collection<Long> zoneIds, StatInterval interval, LocalDateTime from, LocalDateTime to) {
        List<LocalDateTime> buckets = buckets(interval, from, to);
        LocalDateTime end = interval.next(buckets.get(buckets.size() - 1));
        Map<Long, Map<LocalDateTime, ZoneRow>> rowsByZone = zoneIds.isEmpty()
                ? Map.of()
                : findRows(zoneIds, interval, buckets.get(0), end).stream()
                        .filter(row -> row.values().sampleSec() > 0)
                        .collect(Collectors.groupingBy(
                                ZoneRow::zoneId, Collectors.toMap(ZoneRow::at, row -> row)));
        Map<Long, List<StatSeriesPoint>> result = new LinkedHashMap<>();
        for (Long zoneId : zoneIds) {
            // 구역 대상은 저장된 밀집도를 그대로 쓰므로 면적은 쓰지 않는다
            Target target = new Target(LevelTarget.zone(zoneId), Map.of(zoneId, BigDecimal.ZERO), true);
            Map<LocalDateTime, ZoneRow> rows = rowsByZone.getOrDefault(zoneId, Map.of());
            List<StatSeriesPoint> points = new ArrayList<>(buckets.size());
            for (LocalDateTime at : buckets) {
                ZoneRow row = rows.get(at);
                points.add(row == null
                        ? StatSeriesPoint.empty(at.atOffset(KST_OFFSET))
                        : toPoint(target, at, List.of(row), Map.of()));
            }
            result.put(zoneId, points);
        }
        return result;
    }

    // from이 구간 중간이면 그 구간의 시작부터 세고, to 앞에서 시작한 마지막 구간까지 통째로 넣는다(#25에서 정함)
    private static List<LocalDateTime> buckets(StatInterval interval, LocalDateTime from, LocalDateTime to) {
        List<LocalDateTime> buckets = new ArrayList<>();
        for (LocalDateTime at = interval.floor(from); at.isBefore(to); at = interval.next(at)) {
            buckets.add(at);
        }
        return buckets;
    }

    private Target zoneTarget(long zoneId) {
        // 삭제된 구역도 받는다: 삭제 표시된 구역의 지난 통계를 그래프로 볼 수 있어야 한다(FNC-A-017 업무 규칙, #183)
        Zone zone = regionQueryService.getZoneIncludingDeleted(zoneId);
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
    private Map<LocalDateTime, List<ZoneRow>> findSampledRows(
            Target target, StatInterval interval, LocalDateTime from, LocalDateTime to) {
        if (target.areaByZone().isEmpty()) {
            return Map.of();
        }
        return findRows(target.areaByZone().keySet(), interval, from, to).stream()
                .filter(row -> row.values().sampleSec() > 0)
                .collect(Collectors.groupingBy(ZoneRow::at));
    }

    private List<ZoneRow> findRows(
            Collection<Long> zoneIds, StatInterval interval, LocalDateTime from, LocalDateTime to) {
        return switch (interval) {
            case MINUTE -> zoneStatMinuteRepository
                    .findByZoneIdInAndStatAtGreaterThanEqualAndStatAtLessThan(zoneIds, from, to).stream()
                    // 1분 통계에는 시연용 데이터가 없다(tables.md zone_stat_minute)
                    .map(row -> new ZoneRow(row.getZoneId(), row.getStatAt(), StatRollupCalculator.of(row), false))
                    .toList();
            case HOUR -> zoneStatHourlyRepository
                    .findByZoneIdInAndStatAtGreaterThanEqualAndStatAtLessThan(zoneIds, from, to).stream()
                    .map(row -> new ZoneRow(row.getZoneId(), row.getStatAt(), row.values(), row.isDemo()))
                    .toList();
            case DAY -> zoneStatDailyRepository
                    .findByZoneIdInAndStatDateGreaterThanEqualAndStatDateLessThan(
                            zoneIds, from.toLocalDate(), to.toLocalDate())
                    .stream()
                    .map(row -> new ZoneRow(
                            row.getZoneId(), row.getStatDate().atStartOfDay(), row.values(), row.isDemo()))
                    .toList();
            case WEEK, MONTH, YEAR -> rollUpDaily(zoneIds, interval, from, to);
        };
    }

    // 구역마다 같은 구간(주·월·연)의 일별 통계를 #146(FNC-A-029·030)과 같은 sample_sec 가중 평균으로 묶는다.
    // 받은 초가 있는 날이 없으면 그 구간은 행을 만들지 않는다. 묶은 날 중 하나라도 시연용이면 시연용(FNC-C-024)
    private List<ZoneRow> rollUpDaily(
            Collection<Long> zoneIds, StatInterval interval, LocalDateTime from, LocalDateTime to) {
        Map<Long, Map<LocalDateTime, List<ZoneStatDaily>>> daysByZoneAndBucket = zoneStatDailyRepository
                .findByZoneIdInAndStatDateGreaterThanEqualAndStatDateLessThan(
                        zoneIds, from.toLocalDate(), to.toLocalDate())
                .stream()
                .collect(Collectors.groupingBy(
                        ZoneStatDaily::getZoneId,
                        Collectors.groupingBy(row -> interval.floor(row.getStatDate().atStartOfDay()))));
        List<ZoneRow> result = new ArrayList<>();
        daysByZoneAndBucket.forEach((zoneId, daysByBucket) -> daysByBucket.forEach((bucket, days) -> {
            List<StatValues> values = days.stream().map(ZoneStatDaily::values).toList();
            boolean demo = days.stream().anyMatch(ZoneStatDaily::isDemo);
            StatRollupCalculator.rollUp(values)
                    .ifPresent(rolledUp -> result.add(new ZoneRow(zoneId, bucket, rolledUp, demo)));
        }));
        return result;
    }

    private StatSeriesPoint toPoint(
            Target target, LocalDateTime at, List<ZoneRow> rows, Map<Long, Map<ZoneDirection, Long>> neighbors) {
        List<StatValues> values = rows.stream().map(ZoneRow::values).toList();
        BigDecimal countAvg = values.stream().map(StatValues::countAvg).reduce(BigDecimal.ZERO, BigDecimal::add);
        // 구역 하나면 저장된 밀집도(당시 면적 기준)를 그대로 쓴다. 지역은 합산한 구역의 현재 면적 합으로 나눈다
        BigDecimal density = target.isZone()
                ? values.get(0).density()
                : countAvg.divide(sumArea(target, rows), DENSITY_SCALE, RoundingMode.HALF_UP);
        long dwellSumSec = values.stream().mapToLong(StatValues::dwellSumSec).sum();
        long dwellCount = values.stream().mapToLong(StatValues::dwellCount).sum();
        BigDecimal dwellAvgSec = dwellCount == 0
                ? null
                : BigDecimal.valueOf(dwellSumSec)
                        .divide(BigDecimal.valueOf(dwellCount), DWELL_SCALE, RoundingMode.HALF_UP);
        return new StatSeriesPoint(
                at.atOffset(KST_OFFSET),
                countAvg,
                values.stream().mapToInt(StatValues::countMax).sum(),
                values.stream().mapToInt(StatValues::countMin).sum(),
                density,
                levelService.judgeLevel(target.levelTarget(), density).getLevel(),
                sumFlow(target, rows, neighbors, true),
                sumFlow(target, rows, neighbors, false),
                dwellAvgSec,
                values.stream().mapToInt(StatValues::sampleSec).max().orElse(0),
                // 묶은 점에 시연용 행이 하나라도 있으면 시연용(FNC-C-024 처리 순서 2)
                rows.stream().anyMatch(ZoneRow::demo));
    }

    private BigDecimal sumArea(Target target, List<ZoneRow> rows) {
        return rows.stream()
                .map(row -> target.areaByZone().get(row.zoneId()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    // 방향 d의 이웃이 지역 안 구역이면 그 방향의 유입·유출은 지역 안 이동이라 세지 않는다(FNC-C-012 업무 규칙)
    private Map<String, Integer> sumFlow(
            Target target, List<ZoneRow> rows, Map<Long, Map<ZoneDirection, Long>> neighbors, boolean inflow) {
        Set<Long> targetZoneIds = target.areaByZone().keySet();
        Map<String, Integer> result = new LinkedHashMap<>();
        for (ZoneDirection direction : ZoneDirection.values()) {
            ToIntFunction<StatValues> column = inflow ? inflowColumn(direction) : outflowColumn(direction);
            int sum = 0;
            for (ZoneRow row : rows) {
                Long neighborId = neighbors.getOrDefault(row.zoneId(), Map.of()).get(direction);
                if (neighborId == null || !targetZoneIds.contains(neighborId)) {
                    sum += column.applyAsInt(row.values());
                }
            }
            result.put(direction.name(), sum);
        }
        return result;
    }

    private static ToIntFunction<StatValues> inflowColumn(ZoneDirection direction) {
        return switch (direction) {
            case N -> StatValues::inN;
            case E -> StatValues::inE;
            case S -> StatValues::inS;
            case W -> StatValues::inW;
        };
    }

    private static ToIntFunction<StatValues> outflowColumn(ZoneDirection direction) {
        return switch (direction) {
            case N -> StatValues::outN;
            case E -> StatValues::outE;
            case S -> StatValues::outS;
            case W -> StatValues::outW;
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

    /** 원본 표와 관계없이 계산에 쓰는 한 구역·한 구간의 통계. */
    private record ZoneRow(Long zoneId, LocalDateTime at, StatValues values, boolean demo) {
    }
}
