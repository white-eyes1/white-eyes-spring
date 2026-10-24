package com.whiteeyes.common.stat.service;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToIntFunction;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.whiteeyes.common.region.entity.Region;
import com.whiteeyes.common.region.entity.Zone;
import com.whiteeyes.common.region.entity.ZoneDirection;
import com.whiteeyes.common.region.entity.ZoneNeighbor;
import com.whiteeyes.common.region.repository.ZoneNeighborRepository;
import com.whiteeyes.common.region.service.RegionQueryService;
import com.whiteeyes.common.stat.dto.ZoneFlowItem;
import com.whiteeyes.common.stat.dto.ZoneFlowRequest;
import com.whiteeyes.common.stat.entity.StatValues;
import com.whiteeyes.common.stat.repository.ZoneStatDailyRepository;
import com.whiteeyes.common.stat.repository.ZoneStatHourlyRepository;
import com.whiteeyes.common.stat.repository.ZoneStatMinuteRepository;

/**
 * 구역 간 이동 흐름(FNC-C-010). 지역 안 구역마다 기간의 방향별 유출 합을 내고, 그 방향의 이웃(현재 zone_neighbor)으로 간 인원으로 본다.
 * 이웃이 없는 방향은 관측 범위 밖(toZoneId=null)이다. 0인 이동은 보내지 않는다.
 *
 * <p>원본은 기간을 나눠 고른다(처리 순서 2): 하루가 통째로 들어가는 구간은 일별, 남은 구간 중 한 시간이 통째로 들어가는 구간은
 * 시간별, 나머지(1시간 미만)는 1분 통계. 예: 10:30 ~ 다음 날 12:15 → 10:30~11:00 1분, 11:00~24:00 시간별,
 * 다음 날 0:00~12:00 시간별, 12:00~12:15 1분(하루가 통째로 들어가지 않음).
 */
@Service
public class ZoneFlowService {

    private final RegionQueryService regionQueryService;
    private final ZoneNeighborRepository zoneNeighborRepository;
    private final ZoneStatMinuteRepository zoneStatMinuteRepository;
    private final ZoneStatHourlyRepository zoneStatHourlyRepository;
    private final ZoneStatDailyRepository zoneStatDailyRepository;

    public ZoneFlowService(
            RegionQueryService regionQueryService,
            ZoneNeighborRepository zoneNeighborRepository,
            ZoneStatMinuteRepository zoneStatMinuteRepository,
            ZoneStatHourlyRepository zoneStatHourlyRepository,
            ZoneStatDailyRepository zoneStatDailyRepository) {
        this.regionQueryService = regionQueryService;
        this.zoneNeighborRepository = zoneNeighborRepository;
        this.zoneStatMinuteRepository = zoneStatMinuteRepository;
        this.zoneStatHourlyRepository = zoneStatHourlyRepository;
        this.zoneStatDailyRepository = zoneStatDailyRepository;
    }

    /**
     * @return 나간 구역 번호·방향(N·E·S·W) 순
     * @throws com.whiteeyes.common.region.exception.RegionNotFoundException 없는 지역이면(404)
     */
    @Transactional(readOnly = true)
    public List<ZoneFlowItem> findFlows(ZoneFlowRequest request) {
        Region region = regionQueryService.getRegion(request.regionId());
        List<Long> dongIds = regionQueryService.findEmdsUnder(region).stream().map(Region::getId).toList();
        // 지도에 그리는 구역(삭제되지 않은 구역)만 화살표의 출발점이 된다
        List<Long> zoneIds = dongIds.isEmpty()
                ? List.of()
                : regionQueryService.findZonesInDongs(dongIds).stream().map(Zone::getId).sorted().toList();
        if (zoneIds.isEmpty()) {
            return List.of();
        }

        Map<Long, long[]> outflowByZone = new HashMap<>();
        for (Segment segment : split(request.from(), request.to())) {
            for (ZoneValues row : findRows(zoneIds, segment)) {
                long[] outflow = outflowByZone.computeIfAbsent(row.zoneId(), zoneId -> new long[4]);
                for (ZoneDirection direction : ZoneDirection.values()) {
                    outflow[direction.ordinal()] += outflowColumn(direction).applyAsInt(row.values());
                }
            }
        }
        Map<Long, Map<ZoneDirection, Long>> neighbors = findNeighbors(zoneIds);

        List<ZoneFlowItem> result = new ArrayList<>();
        for (Long zoneId : zoneIds) {
            long[] outflow = outflowByZone.get(zoneId);
            if (outflow == null) {
                continue;
            }
            for (ZoneDirection direction : ZoneDirection.values()) {
                long count = outflow[direction.ordinal()];
                if (count > 0) {
                    Long toZoneId = neighbors.getOrDefault(zoneId, Map.of()).get(direction);
                    result.add(new ZoneFlowItem(zoneId, toZoneId, direction.name(), count));
                }
            }
        }
        return result;
    }

    /** 기간을 원본 단위가 같은 구간으로 나눈다. 앞뒤로 갈수록 작은 단위다. */
    static List<Segment> split(LocalDateTime from, LocalDateTime to) {
        List<Segment> segments = new ArrayList<>();
        LocalDateTime cursor = from;
        // 1) 정시까지는 1분
        LocalDateTime nextHour = ceil(cursor, ChronoUnit.HOURS);
        if (nextHour.isAfter(cursor)) {
            LocalDateTime end = nextHour.isBefore(to) ? nextHour : to;
            segments.add(new Segment(Source.MINUTE, cursor, end));
            cursor = end;
        }
        // 2) 자정까지 통째로 들어가는 시간은 시간별
        LocalDateTime nextDay = ceil(cursor, ChronoUnit.DAYS);
        LocalDateTime hoursEnd = earlier(nextDay, floor(to, ChronoUnit.HOURS));
        if (hoursEnd.isAfter(cursor)) {
            segments.add(new Segment(Source.HOUR, cursor, hoursEnd));
            cursor = hoursEnd;
        }
        // 3) 통째로 들어가는 날은 일별
        LocalDateTime daysEnd = floor(to, ChronoUnit.DAYS);
        if (daysEnd.isAfter(cursor) && cursor.equals(floor(cursor, ChronoUnit.DAYS))) {
            segments.add(new Segment(Source.DAY, cursor, daysEnd));
            cursor = daysEnd;
        }
        // 4) 남은 통째 시간은 시간별, 5) 나머지 분은 1분
        LocalDateTime lastHoursEnd = floor(to, ChronoUnit.HOURS);
        if (lastHoursEnd.isAfter(cursor)) {
            segments.add(new Segment(Source.HOUR, cursor, lastHoursEnd));
            cursor = lastHoursEnd;
        }
        if (to.isAfter(cursor)) {
            segments.add(new Segment(Source.MINUTE, cursor, to));
        }
        return segments;
    }

    private List<ZoneValues> findRows(List<Long> zoneIds, Segment segment) {
        return switch (segment.source()) {
            case MINUTE -> zoneStatMinuteRepository
                    .findByZoneIdInAndStatAtGreaterThanEqualAndStatAtLessThan(zoneIds, segment.from(), segment.to())
                    .stream()
                    .map(row -> new ZoneValues(row.getZoneId(), StatRollupCalculator.of(row)))
                    .toList();
            case HOUR -> zoneStatHourlyRepository
                    .findByZoneIdInAndStatAtGreaterThanEqualAndStatAtLessThan(zoneIds, segment.from(), segment.to())
                    .stream()
                    .map(row -> new ZoneValues(row.getZoneId(), row.values()))
                    .toList();
            case DAY -> zoneStatDailyRepository
                    .findByZoneIdInAndStatDateGreaterThanEqualAndStatDateLessThan(
                            zoneIds, segment.from().toLocalDate(), segment.to().toLocalDate())
                    .stream()
                    .map(row -> new ZoneValues(row.getZoneId(), row.values()))
                    .toList();
        };
    }

    // 지금의 이웃 설정으로 계산한다(업무 규칙, 알려진 한계 14)
    private Map<Long, Map<ZoneDirection, Long>> findNeighbors(List<Long> zoneIds) {
        Map<Long, Map<ZoneDirection, Long>> result = new HashMap<>();
        for (ZoneNeighbor row : zoneNeighborRepository.findByZoneIdIn(zoneIds)) {
            result.computeIfAbsent(row.getZoneId(), zoneId -> new EnumMap<>(ZoneDirection.class))
                    .put(row.getDirection(), row.getNeighborId());
        }
        return result;
    }

    private static ToIntFunction<StatValues> outflowColumn(ZoneDirection direction) {
        return switch (direction) {
            case N -> StatValues::outN;
            case E -> StatValues::outE;
            case S -> StatValues::outS;
            case W -> StatValues::outW;
        };
    }

    private static LocalDateTime floor(LocalDateTime time, ChronoUnit unit) {
        return time.truncatedTo(unit);
    }

    private static LocalDateTime ceil(LocalDateTime time, ChronoUnit unit) {
        LocalDateTime floor = time.truncatedTo(unit);
        return floor.equals(time) ? time : floor.plus(1, unit);
    }

    private static LocalDateTime earlier(LocalDateTime a, LocalDateTime b) {
        return Comparator.<LocalDateTime>naturalOrder().compare(a, b) <= 0 ? a : b;
    }

    /** 원본 표. */
    enum Source {
        MINUTE,
        HOUR,
        DAY
    }

    /** 한 원본에서 읽는 구간(from 이상 to 미만). */
    record Segment(Source source, LocalDateTime from, LocalDateTime to) {
    }

    /** 원본과 관계없이 계산에 쓰는 한 구역의 한 행. */
    private record ZoneValues(Long zoneId, StatValues values) {
    }
}
