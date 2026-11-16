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

import lombok.RequiredArgsConstructor;
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
@RequiredArgsConstructor
public class StatSeriesService {

    // 응답의 시각에 붙이는 한국 시간 오프셋(+09:00)
    private static final ZoneOffset KST_OFFSET = ZoneOffset.ofHours(9);
    // zone_stat_*.density와 같은 자릿수·반올림(DECIMAL 8,4)
    private static final int DENSITY_SCALE = 4;
    // 평균 체류 시간은 소수 첫째 자리까지(api/public.md 예시 312.5)
    private static final int DWELL_SCALE = 1;

    // 지역 → 읍·면·동 → 구역 목록과 구역 존재 확인(없으면 404)을 맡는다
    private final RegionQueryService regionQueryService;
    // 1m 단위의 원본
    private final ZoneStatMinuteRepository zoneStatMinuteRepository;
    // 1h 단위의 원본
    private final ZoneStatHourlyRepository zoneStatHourlyRepository;
    // 1d 단위의 원본이자 1w·1M·1y를 만들 때 묶는 재료
    private final ZoneStatDailyRepository zoneStatDailyRepository;
    // 지역 합산에서 '지역 안 구역끼리의 이동'을 빼려고 방향별 이웃을 읽는다
    private final ZoneNeighborRepository zoneNeighborRepository;
    // 밀집도를 혼잡 단계(1~3)로 바꾼다
    private final LevelService levelService;

    /**
     * @throws com.whiteeyes.common.region.exception.RegionNotFoundException 없는 지역이면(404)
     * @throws com.whiteeyes.common.region.exception.ZoneNotFoundException 없는 구역이면(404). 삭제된 구역은 지난 통계를 보므로 받는다
     */
    @Transactional(readOnly = true)
    public StatSeriesResponse findSeries(StatSeriesRequest request) {
        // 구역 하나 또는 지역 아래 구역 모음을 준비한다. 없는 대상이면 여기서 404
        Target target = request.targetType() == StatTargetType.ZONE
                ? zoneTarget(request.targetId())
                : regionTarget(request.targetId());
        StatInterval interval = request.interval();
        // 조회 기간의 모든 구간 시작 시각. 통계가 없는 구간도 점이 되도록 먼저 구간 목록부터 만든다
        List<LocalDateTime> buckets = buckets(interval, request.from(), request.to());
        // 원본 조회의 끝(미포함)은 마지막 구간의 다음 구간 시작이다. 마지막 구간을 통째로 읽기 위해서다
        LocalDateTime end = interval.next(buckets.get(buckets.size() - 1));
        // 구간 시작 시각 → 그 시각의 구역별 행. 받은 초가 없는 행은 이미 빠져 있다
        Map<LocalDateTime, List<ZoneRow>> rowsByAt = findSampledRows(target, interval, buckets.get(0), end);
        // 구역 하나는 이웃을 따질 필요가 없고, 지역일 때만 구역별 방향 → 이웃 번호를 읽는다
        Map<Long, Map<ZoneDirection, Long>> neighborsByZone = target.isZone() ? Map.of() : findNeighbors(target);

        List<StatSeriesPoint> items = new ArrayList<>();
        for (LocalDateTime at : buckets) {
            // 행이 없으면 통계 없는 구간이다. 점을 빼지 않고 빈 점으로 채워 그래프가 끊기게 한다
            List<ZoneRow> rows = rowsByAt.get(at);
            items.add(
                    rows == null
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
        // 구역 번호 → (구간 시작 시각 → 행). 구역 수와 관계없이 원본을 한 번만 읽는다
        Map<Long, Map<LocalDateTime, ZoneRow>> rowsByZone = zoneIds.isEmpty()
                ? Map.of()
                : findRows(zoneIds, interval, buckets.get(0), end).stream()
                        // 받은 초가 0인 행은 측정이 없는 것이라 빼서 빈 점으로 처리한다
                        .filter(row -> row.values().sampleSec() > 0)
                        .collect(Collectors.groupingBy(ZoneRow::zoneId, Collectors.toMap(ZoneRow::at, row -> row)));
        Map<Long, List<StatSeriesPoint>> result = new LinkedHashMap<>();
        // 요청받은 구역 순서대로 결과를 만든다(결과가 LinkedHashMap이라 순서가 유지된다)
        for (Long zoneId : zoneIds) {
            // 구역 대상은 저장된 밀집도를 그대로 쓰므로 면적은 쓰지 않는다
            Target target = new Target(LevelTarget.zone(zoneId), Map.of(zoneId, BigDecimal.ZERO), true);
            Map<LocalDateTime, ZoneRow> rows = rowsByZone.getOrDefault(zoneId, Map.of());
            // 모든 구역이 같은 구간 목록을 가지므로 점 개수가 같다
            List<StatSeriesPoint> points = new ArrayList<>(buckets.size());
            for (LocalDateTime at : buckets) {
                ZoneRow row = rows.get(at);
                points.add(
                        row == null
                                ? StatSeriesPoint.empty(at.atOffset(KST_OFFSET))
                                // 구역 하나의 행 하나로 점을 만든다. 구역 대상이라 이웃 정보는 필요 없다
                                : toPoint(target, at, List.of(row), Map.of()));
            }
            result.put(zoneId, points);
        }
        return result;
    }

    /**
     * 아직 시간별 통계가 없는 구간(진행 중인 시간)을 1분 통계로 묶은 점 하나(FNC-C-018 처리 순서 3).
     * 구역마다 1분 통계를 시간별 집계(FNC-A-029)와 같은 계산으로 묶은 뒤, 지역은 {@link #findSeries}의 점과 같은 규칙으로 합산한다.
     *
     * @param from 구간 시작(점의 at)
     * @param to 구간 끝(미만)
     * @return 받은 초가 있는 1분 통계가 없으면 빈 점
     * @throws com.whiteeyes.common.region.exception.RegionNotFoundException 없는 지역이면(404)
     * @throws com.whiteeyes.common.region.exception.ZoneNotFoundException 없는 구역이면(404)
     */
    @Transactional(readOnly = true)
    public StatSeriesPoint findMinuteRollup(
            StatTargetType targetType, long targetId, LocalDateTime from, LocalDateTime to) {
        Target target = targetType == StatTargetType.ZONE ? zoneTarget(targetId) : regionTarget(targetId);
        // 구역이 없는 지역이면 읽을 것이 없다
        if (target.areaByZone().isEmpty()) {
            return StatSeriesPoint.empty(from.atOffset(KST_OFFSET));
        }
        // 구역별로 그 시간의 1분 통계 값들을 모은다
        Map<Long, List<StatValues>> minutesByZone =
                findRows(target.areaByZone().keySet(), StatInterval.MINUTE, from, to).stream()
                        .collect(Collectors.groupingBy(
                                ZoneRow::zoneId, Collectors.mapping(ZoneRow::values, Collectors.toList())));
        List<ZoneRow> rolledUp = new ArrayList<>();
        // 1분 통계에는 시연용 데이터가 없다(tables.md zone_stat_minute)
        // 구역마다 시간별 집계와 같은 계산으로 한 행으로 묶는다. 받은 초가 있는 분이 없으면 그 구역은 빠진다
        minutesByZone.forEach((zoneId, minutes) -> StatRollupCalculator.rollUp(minutes)
                .ifPresent(values -> rolledUp.add(new ZoneRow(zoneId, from, values, false))));
        // 묶을 수 있는 구역이 하나도 없으면 빈 점
        if (rolledUp.isEmpty()) {
            return StatSeriesPoint.empty(from.atOffset(KST_OFFSET));
        }
        Map<Long, Map<ZoneDirection, Long>> neighborsByZone = target.isZone() ? Map.of() : findNeighbors(target);
        // 묶은 구역 행들을 일반 시계열과 같은 규칙으로 점 하나로 합친다
        return toPoint(target, from, rolledUp, neighborsByZone);
    }

    // from이 구간 중간이면 그 구간의 시작부터 세고, to 앞에서 시작한 마지막 구간까지 통째로 넣는다(#25에서 정함)
    private static List<LocalDateTime> buckets(StatInterval interval, LocalDateTime from, LocalDateTime to) {
        List<LocalDateTime> buckets = new ArrayList<>();
        // from이 속한 구간부터 시작해 to 앞에서 시작하는 마지막 구간까지 모은다
        for (LocalDateTime at = interval.floor(from); at.isBefore(to); at = interval.next(at)) {
            buckets.add(at);
        }
        return buckets;
    }

    private Target zoneTarget(long zoneId) {
        // 삭제된 구역도 받는다: 삭제 표시된 구역의 지난 통계를 그래프로 볼 수 있어야 한다(FNC-A-017 업무 규칙, #183)
        // 지난 통계를 봐야 하므로 삭제된 구역도 허용한다(없으면 404)
        Zone zone = regionQueryService.getZoneIncludingDeleted(zoneId);
        return new Target(LevelTarget.zone(zoneId), Map.of(zoneId, zone.getAreaM2()), true);
    }

    // 지난 통계를 보므로 삭제된 구역도 넣는다(FNC-C-012 처리 순서 4). 기간에 통계가 없는 구역은 결과에 영향이 없다
    private Target regionTarget(long regionId) {
        Region region = regionQueryService.getRegion(regionId);
        List<Long> dongIds = regionQueryService.findEmdsUnder(region).stream()
                .map(Region::getId)
                .toList();
        // 구역 번호 → 현재 면적(㎡). 합산 대상 구역을 정하는 동시에 지역 밀집도를 나눌 면적으로 쓴다
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
        // 구역 번호를 한 번에 넘겨 원본을 읽는다(구역마다 조회하지 않는다)
        return findRows(target.areaByZone().keySet(), interval, from, to).stream()
                .filter(row -> row.values().sampleSec() > 0)
                // 시각별로 묶어 같은 시각의 구역 행들을 한자리에 모은다
                .collect(Collectors.groupingBy(ZoneRow::at));
    }

    /** 단위에 맞는 원본 표에서 구역들의 행을 읽어 같은 모양(ZoneRow)으로 맞춘다. 구간은 from 이상 to 미만이다 */
    private List<ZoneRow> findRows(
            Collection<Long> zoneIds, StatInterval interval, LocalDateTime from, LocalDateTime to) {
        return switch (interval) {
            case MINUTE -> zoneStatMinuteRepository
                    .findByZoneIdInAndStatAtGreaterThanEqualAndStatAtLessThan(zoneIds, from, to)
                    .stream()
                    // 1분 통계에는 시연용 데이터가 없다(tables.md zone_stat_minute)
                    .map(row -> new ZoneRow(row.getZoneId(), row.getStatAt(), StatRollupCalculator.of(row), false))
                    .toList();
            case HOUR -> zoneStatHourlyRepository
                    .findByZoneIdInAndStatAtGreaterThanEqualAndStatAtLessThan(zoneIds, from, to)
                    .stream()
                    .map(row -> new ZoneRow(row.getZoneId(), row.getStatAt(), row.values(), row.isDemo()))
                    .toList();
            case DAY -> zoneStatDailyRepository
                    .findByZoneIdInAndStatDateGreaterThanEqualAndStatDateLessThan(
                            // 일별 통계는 날짜 단위라 시각을 날짜로 바꿔 넘긴다
                            zoneIds, from.toLocalDate(), to.toLocalDate())
                    .stream()
                    .map(row ->
                            new ZoneRow(row.getZoneId(), row.getStatDate().atStartOfDay(), row.values(), row.isDemo()))
                    .toList();
                // 주·월·연은 전용 표가 없어 일별 통계를 읽어 구역별로 묶는다
            case WEEK, MONTH, YEAR -> rollUpDaily(zoneIds, interval, from, to);
        };
    }

    // 구역마다 같은 구간(주·월·연)의 일별 통계를 #146(FNC-A-029·030)과 같은 sample_sec 가중 평균으로 묶는다.
    // 받은 초가 있는 날이 없으면 그 구간은 행을 만들지 않는다. 묶은 날 중 하나라도 시연용이면 시연용(FNC-C-024)
    private List<ZoneRow> rollUpDaily(
            Collection<Long> zoneIds, StatInterval interval, LocalDateTime from, LocalDateTime to) {
        // 구역 번호 → (주/월/연의 시작 시각 → 그 구간에 속한 일별 행들)
        Map<Long, Map<LocalDateTime, List<ZoneStatDaily>>> daysByZoneAndBucket = zoneStatDailyRepository
                .findByZoneIdInAndStatDateGreaterThanEqualAndStatDateLessThan(
                        zoneIds, from.toLocalDate(), to.toLocalDate())
                .stream()
                .collect(Collectors.groupingBy(
                        ZoneStatDaily::getZoneId,
                        Collectors.groupingBy(
                                // 일별 날짜를 해당 단위(월요일·1일·1월 1일)의 시작 시각으로 내려서 같은 구간끼리 묶는다
                                row -> interval.floor(row.getStatDate().atStartOfDay()))));
        List<ZoneRow> result = new ArrayList<>();
        daysByZoneAndBucket.forEach((zoneId, daysByBucket) -> daysByBucket.forEach((bucket, days) -> {
            List<StatValues> values = days.stream().map(ZoneStatDaily::values).toList();
            // 묶은 날 중 하나라도 시연용이면 그 구간 전체를 시연용으로 표시한다
            boolean demo = days.stream().anyMatch(ZoneStatDaily::isDemo);
            StatRollupCalculator.rollUp(values)
                    .ifPresent(rolledUp -> result.add(new ZoneRow(zoneId, bucket, rolledUp, demo)));
        }));
        return result;
    }

    private StatSeriesPoint toPoint(
            Target target, LocalDateTime at, List<ZoneRow> rows, Map<Long, Map<ZoneDirection, Long>> neighbors) {
        // 점 하나를 만들 재료. 지역이면 구역 수만큼, 구역이면 하나다
        List<StatValues> values = rows.stream().map(ZoneRow::values).toList();
        // 지역의 인원은 구역별 평균 인원의 합이다
        BigDecimal countAvg = values.stream().map(StatValues::countAvg).reduce(BigDecimal.ZERO, BigDecimal::add);
        // 구역 하나면 저장된 밀집도(당시 면적 기준)를 그대로 쓴다. 지역은 합산한 구역의 현재 면적 합으로 나눈다
        BigDecimal density = target.isZone()
                ? values.get(0).density()
                : countAvg.divide(sumArea(target, rows), DENSITY_SCALE, RoundingMode.HALF_UP);
        // 평균 체류 시간 = 체류 시간 합 ÷ 체류를 잰 사람 수. 구역별 평균을 평균내지 않고 합끼리 나눠야 사람 수 비중이 맞는다
        long dwellSumSec = values.stream().mapToLong(StatValues::dwellSumSec).sum();
        long dwellCount = values.stream().mapToLong(StatValues::dwellCount).sum();
        // 체류를 잰 사람이 없으면 평균을 낼 수 없어 null이다
        BigDecimal dwellAvgSec = dwellCount == 0
                ? null
                : BigDecimal.valueOf(dwellSumSec)
                        .divide(BigDecimal.valueOf(dwellCount), DWELL_SCALE, RoundingMode.HALF_UP);
        return new StatSeriesPoint(
                at.atOffset(KST_OFFSET),
                countAvg,
                // 지역의 최대·최소 인원은 구역별 최대·최소의 합이다(구역 하나면 그 값 그대로)
                values.stream().mapToInt(StatValues::countMax).sum(),
                values.stream().mapToInt(StatValues::countMin).sum(),
                density,
                // 밀집도를 대상(구역/지역)별 기준값과 비교해 혼잡 단계(1~3)로 바꾼다
                levelService.judgeLevel(target.levelTarget(), density).getLevel(),
                // 유입(true)과 유출(false)을 각각 방향별로 합산한다. 지역 안 구역끼리 오간 이동은 뺀다
                sumFlow(target, rows, neighbors, true),
                sumFlow(target, rows, neighbors, false),
                dwellAvgSec,
                // 지역은 합산한 구역 중 받은 초가 가장 많은 값을 쓴다(구역마다 다를 수 있어 합이 아니라 최댓값)
                values.stream().mapToInt(StatValues::sampleSec).max().orElse(0),
                // 묶은 점에 시연용 행이 하나라도 있으면 시연용(FNC-C-024 처리 순서 2)
                rows.stream().anyMatch(ZoneRow::demo));
    }

    /** 이 점에 들어간 구역들의 현재 면적 합. 받은 초가 없어 빠진 구역의 면적은 넣지 않는다 */
    private BigDecimal sumArea(Target target, List<ZoneRow> rows) {
        return rows.stream().map(row -> target.areaByZone().get(row.zoneId())).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    // 방향 d의 이웃이 지역 안 구역이면 그 방향의 유입·유출은 지역 안 이동이라 세지 않는다(FNC-C-012 업무 규칙)
    private Map<String, Integer> sumFlow(
            Target target, List<ZoneRow> rows, Map<Long, Map<ZoneDirection, Long>> neighbors, boolean inflow) {
        // 이웃이 '이 지역 안의 구역'인지 판단하려고 대상 구역 번호를 집합으로 둔다
        Set<Long> targetZoneIds = target.areaByZone().keySet();
        Map<String, Integer> result = new LinkedHashMap<>();
        for (ZoneDirection direction : ZoneDirection.values()) {
            // 유입이면 in_*, 유출이면 out_* 칼럼 중 이 방향의 것을 꺼내는 함수를 고른다
            ToIntFunction<StatValues> column = inflow ? inflowColumn(direction) : outflowColumn(direction);
            int sum = 0;
            for (ZoneRow row : rows) {
                Long neighborId = neighbors.getOrDefault(row.zoneId(), Map.of()).get(direction);
                // 이웃이 없거나 지역 밖 구역이면 지역을 드나든 실제 이동이므로 센다. 지역 안 구역과 주고받은 것만 제외된다
                if (neighborId == null || !targetZoneIds.contains(neighborId)) {
                    sum += column.applyAsInt(row.values());
                }
            }
            // 키는 N·E·S·W 순서로 고정된다(LinkedHashMap)
            result.put(direction.name(), sum);
        }
        return result;
    }

    /** 방향에 해당하는 유입 칼럼을 꺼내는 함수를 고른다 */
    private static ToIntFunction<StatValues> inflowColumn(ZoneDirection direction) {
        return switch (direction) {
            case N -> StatValues::inN;
            case E -> StatValues::inE;
            case S -> StatValues::inS;
            case W -> StatValues::inW;
        };
    }

    /** 방향에 해당하는 유출 칼럼을 꺼내는 함수를 고른다 */
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
        // 지역 구역 번호를 한 번에 넘겨 이웃 설정을 읽는다
        for (ZoneNeighbor row :
                zoneNeighborRepository.findByZoneIdIn(target.areaByZone().keySet())) {
            result.computeIfAbsent(row.getZoneId(), zoneId -> new HashMap<>())
                    .put(row.getDirection(), row.getNeighborId());
        }
        return result;
    }

    /**
     * @param areaByZone 합산 대상 구역 번호 → 현재 면적(㎡). 구역 대상이면 그 구역 하나
     */
    // levelTarget은 혼잡 단계 판정 기준 대상, isZone은 구역 하나인지 여부다
    private record Target(LevelTarget levelTarget, Map<Long, BigDecimal> areaByZone, boolean isZone) {}

    /** 원본 표와 관계없이 계산에 쓰는 한 구역·한 구간의 통계. */
    private record ZoneRow(Long zoneId, LocalDateTime at, StatValues values, boolean demo) {}
}
