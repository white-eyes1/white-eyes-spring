package com.whiteeyes.common.stat.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.whiteeyes.common.region.entity.Region;
import com.whiteeyes.common.region.entity.RegionLevel;
import com.whiteeyes.common.region.entity.Zone;
import com.whiteeyes.common.region.service.RegionQueryService;
import com.whiteeyes.common.stat.dto.HeatmapRequest;
import com.whiteeyes.common.stat.dto.HeatmapResponse;
import com.whiteeyes.common.stat.dto.StatInterval;
import com.whiteeyes.common.stat.dto.StatSeriesPoint;
import com.whiteeyes.common.stat.dto.StatSeriesRequest;
import com.whiteeyes.common.stat.dto.StatTargetType;

/**
 * 시간대별 히트맵(FNC-C-011). 하위 단위마다 기간·요일 조건에 맞는 시간별 점을 시간대(0~23시)별로 평균한다.
 * 시간별 점은 통계 그래프(FNC-C-012)의 1h 계열을 그대로 쓴다(하위 지역은 소속 구역 합산, 지역 안 이동 제외).
 *
 * <p>인원·밀집도는 sample_sec 가중 평균, 유입·유출은 시간당 평균 합계(네 방향 합의 평균)다(처리 순서 2).
 */
@Service
public class HeatmapService {

    private static final int HOURS = 24;
    // 시간별 통계와 같은 자릿수(count_avg 2자리, density 4자리). 유입·유출 평균은 소수 첫째 자리
    private static final int COUNT_SCALE = 2;
    private static final int DENSITY_SCALE = 4;
    private static final int FLOW_SCALE = 1;

    private final RegionQueryService regionQueryService;
    private final StatSeriesService statSeriesService;

    public HeatmapService(RegionQueryService regionQueryService, StatSeriesService statSeriesService) {
        this.regionQueryService = regionQueryService;
        this.statSeriesService = statSeriesService;
    }

    /**
     * @throws com.whiteeyes.common.region.exception.RegionNotFoundException 없는 지역이면(404)
     */
    @Transactional(readOnly = true)
    public HeatmapResponse findHeatmap(HeatmapRequest request) {
        Region region = regionQueryService.getRegion(request.regionId());
        Map<Long, List<StatSeriesPoint>> pointsByUnit = region.getLevel() == RegionLevel.EMD
                ? zoneUnits(region, request)
                : regionUnits(region, request);

        Map<String, List<BigDecimal>> values = new LinkedHashMap<>();
        pointsByUnit.forEach((unitId, points) -> values.put(String.valueOf(unitId), averageByHour(points, request)));
        List<BigDecimal> all = values.values().stream().flatMap(List::stream).filter(Objects::nonNull).toList();
        return new HeatmapResponse(
                List.copyOf(pointsByUnit.keySet()),
                values,
                all.stream().min(Comparator.naturalOrder()).orElse(null),
                all.stream().max(Comparator.naturalOrder()).orElse(null));
    }

    // 읍·면·동의 하위 단위는 지도에 그리는 구역(삭제되지 않은 구역, FNC-C-009 단위 목록과 같음)
    private Map<Long, List<StatSeriesPoint>> zoneUnits(Region dong, HeatmapRequest request) {
        List<Long> zoneIds = regionQueryService.findZonesInDongs(List.of(dong.getId())).stream()
                .map(Zone::getId)
                .sorted()
                .toList();
        if (zoneIds.isEmpty()) {
            return Map.of();
        }
        return statSeriesService.findZoneSeries(zoneIds, StatInterval.HOUR, request.from(), request.to());
    }

    private Map<Long, List<StatSeriesPoint>> regionUnits(Region region, HeatmapRequest request) {
        Map<Long, List<StatSeriesPoint>> result = new LinkedHashMap<>();
        for (Region child : regionQueryService.findChildRegions(region.getId())) {
            List<StatSeriesPoint> points = statSeriesService
                    .findSeries(new StatSeriesRequest(
                            StatTargetType.REGION, child.getId(), StatInterval.HOUR, request.from(), request.to()))
                    .items();
            result.put(child.getId(), points);
        }
        return result;
    }

    private static List<BigDecimal> averageByHour(List<StatSeriesPoint> points, HeatmapRequest request) {
        BigDecimal[] weighted = new BigDecimal[HOURS];
        long[] weights = new long[HOURS];
        for (StatSeriesPoint point : points) {
            // 값이 없는 점(받은 초 0·통계 없음)과 요일 조건에 맞지 않는 점은 넣지 않는다
            if (point.countAvg() == null || !request.dayType().matches(point.at().getDayOfWeek())) {
                continue;
            }
            int hour = point.at().getHour();
            // 인원·밀집도는 받은 초로 가중 평균, 유입·유출은 시간마다 같은 무게(시간당 평균)
            long weight = request.item() == HeatmapRequest.Item.COUNT || request.item() == HeatmapRequest.Item.DENSITY
                    ? point.sampleSec()
                    : 1;
            BigDecimal value = valueOf(point, request.item()).multiply(BigDecimal.valueOf(weight));
            weighted[hour] = weighted[hour] == null ? value : weighted[hour].add(value);
            weights[hour] += weight;
        }
        List<BigDecimal> result = new ArrayList<>(HOURS);
        for (int hour = 0; hour < HOURS; hour++) {
            result.add(weights[hour] == 0
                    ? null
                    : weighted[hour].divide(BigDecimal.valueOf(weights[hour]), scale(request.item()), RoundingMode.HALF_UP));
        }
        return result;
    }

    private static BigDecimal valueOf(StatSeriesPoint point, HeatmapRequest.Item item) {
        return switch (item) {
            case COUNT -> point.countAvg();
            case DENSITY -> point.density();
            case INFLOW -> sum(point.inflow());
            case OUTFLOW -> sum(point.outflow());
        };
    }

    private static BigDecimal sum(Map<String, Integer> flow) {
        return BigDecimal.valueOf(flow.values().stream().mapToLong(Integer::longValue).sum());
    }

    private static int scale(HeatmapRequest.Item item) {
        return switch (item) {
            case COUNT -> COUNT_SCALE;
            case DENSITY -> DENSITY_SCALE;
            case INFLOW, OUTFLOW -> FLOW_SCALE;
        };
    }
}
