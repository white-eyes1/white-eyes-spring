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

import lombok.RequiredArgsConstructor;
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
@RequiredArgsConstructor
public class ZoneFlowService {

    // 지역 아래 읍·면·동과 구역을 찾을 때 쓴다
    private final RegionQueryService regionQueryService;
    // 구역의 방향별 이웃 구역 설정. 유출이 어디로 갔는지 정할 때 쓴다
    private final ZoneNeighborRepository zoneNeighborRepository;
    // 기간의 앞뒤 자투리(1시간 미만)를 읽는 1분 통계
    private final ZoneStatMinuteRepository zoneStatMinuteRepository;
    // 하루 미만이지만 한 시간 이상인 구간을 읽는 시간별 통계
    private final ZoneStatHourlyRepository zoneStatHourlyRepository;
    // 하루가 통째로 들어가는 구간을 읽는 일별 통계(행이 가장 적어 가장 빠르다)
    private final ZoneStatDailyRepository zoneStatDailyRepository;

    /**
     * @return 나간 구역 번호·방향(N·E·S·W) 순
     * @throws com.whiteeyes.common.region.exception.RegionNotFoundException 없는 지역이면(404)
     */
    @Transactional(readOnly = true)
    public List<ZoneFlowItem> findFlows(ZoneFlowRequest request) {
        // 지역을 찾는다. 없으면 404
        Region region = regionQueryService.getRegion(request.regionId());
        // 지역 아래 모든 읍·면·동 번호(구역은 읍·면·동에 속한다)
        List<Long> dongIds = regionQueryService.findEmdsUnder(region).stream()
                .map(Region::getId)
                .toList();
        // 지도에 그리는 구역(삭제되지 않은 구역)만 화살표의 출발점이 된다
        List<Long> zoneIds = dongIds.isEmpty()
                ? List.of()
                : regionQueryService.findZonesInDongs(dongIds).stream()
                        .map(Zone::getId)
                        .sorted()
                        .toList();
        // 그릴 구역이 없으면 빈 목록으로 끝낸다
        if (zoneIds.isEmpty()) {
            return List.of();
        }

        // 구역별로 방향(N·E·S·W 순서) 유출 합을 모은다. 배열 인덱스는 ZoneDirection의 순서값(ordinal)이다
        Map<Long, long[]> outflowByZone = new HashMap<>();
        // 기간을 일별·시간별·1분 구간으로 나눠, 구간마다 알맞은 원본 표에서 읽는다(전부 1분으로 읽으면 행이 너무 많다)
        for (Segment segment : split(request.from(), request.to())) {
            for (ZoneValues row : findRows(zoneIds, segment)) {
                // 구역별 4방향 합계 칸. 처음 보는 구역이면 0으로 채워 만든다
                long[] outflow = outflowByZone.computeIfAbsent(row.zoneId(), zoneId -> new long[4]);
                for (ZoneDirection direction : ZoneDirection.values()) {
                    // 방향마다 해당 유출 칼럼(outN·outE·outS·outW)을 골라 더한다. 세 원본 표의 행이 같은 모양(StatValues)이라 한 코드로 처리된다
                    outflow[direction.ordinal()] += outflowColumn(direction).applyAsInt(row.values());
                }
            }
        }
        // 구역별로 방향 → 이웃 구역 번호를 한 번에 읽는다
        Map<Long, Map<ZoneDirection, Long>> neighbors = findNeighbors(zoneIds);

        List<ZoneFlowItem> result = new ArrayList<>();
        for (Long zoneId : zoneIds) {
            // 통계가 하나도 없는 구역은 건너뛴다
            long[] outflow = outflowByZone.get(zoneId);
            if (outflow == null) {
                continue;
            }
            for (ZoneDirection direction : ZoneDirection.values()) {
                long count = outflow[direction.ordinal()];
                // 0명인 이동은 화살표로 그릴 의미가 없어 보내지 않는다
                if (count > 0) {
                    // 그 방향에 이웃이 설정돼 있지 않으면 null(관측 범위 밖으로 나간 것)
                    Long toZoneId = neighbors.getOrDefault(zoneId, Map.of()).get(direction);
                    result.add(new ZoneFlowItem(zoneId, toZoneId, direction.name(), count));
                }
            }
        }
        return result;
    }

    /** 기간을 원본 단위가 같은 구간으로 나눈다. 앞뒤로 갈수록 작은 단위다. */
    static List<Segment> split(LocalDateTime from, LocalDateTime to) {
        // 구간들은 시간 순서대로 이어 붙는다. cursor가 지금까지 나눈 끝 지점이다
        List<Segment> segments = new ArrayList<>();
        LocalDateTime cursor = from;
        // 1) 정시까지는 1분
        // 시작이 정시가 아니면 다음 정시까지는 1시간이 안 되므로 1분 통계로 읽는다(시작이 정시면 건너뛴다)
        LocalDateTime nextHour = ceil(cursor, ChronoUnit.HOURS);
        if (nextHour.isAfter(cursor)) {
            // 기간이 그 정시 전에 끝나면 끝 시각까지만 읽는다
            LocalDateTime end = nextHour.isBefore(to) ? nextHour : to;
            segments.add(new Segment(Source.MINUTE, cursor, end));
            cursor = end;
        }
        // 2) 자정까지 통째로 들어가는 시간은 시간별
        // 다음 자정까지는 하루가 통째로 들어가지 않으므로 시간별로 읽어야 한다
        LocalDateTime nextDay = ceil(cursor, ChronoUnit.DAYS);
        // 다음 자정과 끝 시각의 정시 중 더 이른 곳까지 시간별로 읽는다
        LocalDateTime hoursEnd = earlier(nextDay, floor(to, ChronoUnit.HOURS));
        if (hoursEnd.isAfter(cursor)) {
            segments.add(new Segment(Source.HOUR, cursor, hoursEnd));
            cursor = hoursEnd;
        }
        // 3) 통째로 들어가는 날은 일별
        // 끝 시각이 속한 날의 0시까지가 통째로 들어가는 날들이다. 시작이 자정에 맞을 때만 일별로 읽는다
        LocalDateTime daysEnd = floor(to, ChronoUnit.DAYS);
        if (daysEnd.isAfter(cursor) && cursor.equals(floor(cursor, ChronoUnit.DAYS))) {
            segments.add(new Segment(Source.DAY, cursor, daysEnd));
            cursor = daysEnd;
        }
        // 4) 남은 통째 시간은 시간별, 5) 나머지 분은 1분
        // 끝 날의 0시부터 끝 시각의 정시까지 남은 통째 시간은 시간별로 읽는다
        LocalDateTime lastHoursEnd = floor(to, ChronoUnit.HOURS);
        if (lastHoursEnd.isAfter(cursor)) {
            segments.add(new Segment(Source.HOUR, cursor, lastHoursEnd));
            cursor = lastHoursEnd;
        }
        // 마지막 정시부터 끝까지 남은 1시간 미만은 1분 통계로 읽는다
        if (to.isAfter(cursor)) {
            segments.add(new Segment(Source.MINUTE, cursor, to));
        }
        return segments;
    }

    /** 구간의 원본 표에서 구역들의 행을 읽어 같은 모양(ZoneValues)으로 맞춘다. 구간은 from 이상 to 미만이다 */
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
                            // 일별 통계는 날짜 단위라 시각을 날짜로 바꿔 넘긴다(이 원본은 하루가 통째로 들어가는 구간에만 쓴다)
                            zoneIds, segment.from().toLocalDate(), segment.to().toLocalDate())
                    .stream()
                    .map(row -> new ZoneValues(row.getZoneId(), row.values()))
                    .toList();
        };
    }

    // 지금의 이웃 설정으로 계산한다(업무 규칙, 알려진 한계 14)
    private Map<Long, Map<ZoneDirection, Long>> findNeighbors(List<Long> zoneIds) {
        Map<Long, Map<ZoneDirection, Long>> result = new HashMap<>();
        // 이웃 설정은 구역 목록으로 한 번에 읽는다. 구역마다 조회하지 않는다
        for (ZoneNeighbor row : zoneNeighborRepository.findByZoneIdIn(zoneIds)) {
            result.computeIfAbsent(row.getZoneId(), zoneId -> new EnumMap<>(ZoneDirection.class))
                    .put(row.getDirection(), row.getNeighborId());
        }
        return result;
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

    /** 시각을 단위 경계로 내린다(정시·자정). 이미 경계면 그대로다 */
    private static LocalDateTime floor(LocalDateTime time, ChronoUnit unit) {
        return time.truncatedTo(unit);
    }

    /** 시각을 단위 경계로 올린다(정시·자정). 이미 경계면 그대로다 */
    private static LocalDateTime ceil(LocalDateTime time, ChronoUnit unit) {
        LocalDateTime floor = time.truncatedTo(unit);
        return floor.equals(time) ? time : floor.plus(1, unit);
    }

    /** 두 시각 중 더 이른 것 */
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
    record Segment(Source source, LocalDateTime from, LocalDateTime to) {}

    /** 원본과 관계없이 계산에 쓰는 한 구역의 한 행. */
    private record ZoneValues(Long zoneId, StatValues values) {}
}
