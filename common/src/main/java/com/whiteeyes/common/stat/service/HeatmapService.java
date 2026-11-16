package com.whiteeyes.common.stat.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import lombok.RequiredArgsConstructor;
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
@RequiredArgsConstructor
public class HeatmapService {

    // 하루의 시(0~23) 칸 수
    private static final int HOURS = 24;
    // 시간별 통계와 같은 자릿수(count_avg 2자리, density 4자리). 유입·유출 평균은 소수 첫째 자리
    private static final int COUNT_SCALE = 2;
    private static final int DENSITY_SCALE = 4;
    private static final int FLOW_SCALE = 1;

    // 상위 지역과 그 하위 단위를 찾을 때 쓴다
    private final RegionQueryService regionQueryService;
    // 하위 단위별 시간 단위(1h) 점을 통계 그래프와 같은 규칙으로 얻으려고 시계열 서비스를 재사용한다
    private final StatSeriesService statSeriesService;

    /**
     * @throws com.whiteeyes.common.region.exception.RegionNotFoundException 없는 지역이면(404)
     */
    @Transactional(readOnly = true)
    public HeatmapResponse findHeatmap(HeatmapRequest request) {
        // 상위 지역을 찾는다. 없으면 404
        Region region = regionQueryService.getRegion(request.regionId());
        // 읍·면·동이면 하위 단위가 구역이고, 그보다 위면 하위 지역이다. 단위 번호 → 시간별 점 목록
        Map<Long, List<StatSeriesPoint>> pointsByUnit =
                region.getLevel() == RegionLevel.EMD ? zoneUnits(region, request) : regionUnits(region, request);

        // 단위마다 0~23시 평균 24개를 만든다. JSON 키가 문자열이라 번호를 문자열로 바꾸고, 순서는 입력 순서를 유지한다
        Map<String, List<BigDecimal>> values = new LinkedHashMap<>();
        pointsByUnit.forEach((unitId, points) -> values.put(String.valueOf(unitId), averageByHour(points, request)));
        // 색 정규화에 쓸 최솟값·최댓값을 구하려고 모든 칸의 값을 모은다(데이터 없는 칸은 제외)
        List<BigDecimal> all = values.values().stream()
                .flatMap(List::stream)
                .filter(Objects::nonNull)
                .toList();
        return new HeatmapResponse(
                // 단위 번호 목록(순서 유지). 화면이 이 순서대로 행을 그린다
                List.copyOf(pointsByUnit.keySet()),
                values,
                all.stream().min(Comparator.naturalOrder()).orElse(null),
                all.stream().max(Comparator.naturalOrder()).orElse(null));
    }

    // 읍·면·동의 하위 단위는 지도에 그리는 구역(삭제되지 않은 구역, FNC-C-009 단위 목록과 같음)
    private Map<Long, List<StatSeriesPoint>> zoneUnits(Region dong, HeatmapRequest request) {
        List<Long> zoneIds = regionQueryService.findZonesInDongs(List.of(dong.getId())).stream()
                // 구역 엔티티에서 번호만 뽑는다
                .map(Zone::getId)
                .sorted()
                .toList();
        // 구역이 없으면 빈 IN 쿼리를 피하고 빈 결과로 끝낸다
        if (zoneIds.isEmpty()) {
            return Map.of();
        }
        // 구역별 시간 단위 점을 한 번에 읽는다(구역마다 조회하지 않는다)
        return statSeriesService.findZoneSeries(zoneIds, StatInterval.HOUR, request.from(), request.to());
    }

    private Map<Long, List<StatSeriesPoint>> regionUnits(Region region, HeatmapRequest request) {
        Map<Long, List<StatSeriesPoint>> result = new LinkedHashMap<>();
        // 하위 지역마다 지역 대상 시계열(소속 구역 합산, 지역 안 이동 제외)을 구한다
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
        // 시(0~23)별로 (값 × 가중치)의 합과 가중치의 합을 모아 가중 평균을 낸다
        BigDecimal[] weighted = new BigDecimal[HOURS];
        long[] weights = new long[HOURS];
        for (StatSeriesPoint point : points) {
            // 값이 없는 점(받은 초 0·통계 없음)과 요일 조건에 맞지 않는 점은 넣지 않는다
            if (point.countAvg() == null
                    || !request.dayType().matches(point.at().getDayOfWeek())) {
                continue;
            }
            // 같은 시의 점들을 날짜와 관계없이 한 칸에 모은다
            int hour = point.at().getHour();
            // 인원·밀집도는 받은 초로 가중 평균, 유입·유출은 시간마다 같은 무게(시간당 평균)
            long weight = request.item() == HeatmapRequest.Item.COUNT || request.item() == HeatmapRequest.Item.DENSITY
                    ? point.sampleSec()
                    : 1;
            BigDecimal value = valueOf(point, request.item()).multiply(BigDecimal.valueOf(weight));
            // 처음 들어오는 값이면 그대로 두고, 아니면 더한다
            weighted[hour] = weighted[hour] == null ? value : weighted[hour].add(value);
            weights[hour] += weight;
        }
        List<BigDecimal> result = new ArrayList<>(HOURS);
        for (int hour = 0; hour < HOURS; hour++) {
            result.add(
                    // 가중치가 0이면(조건에 맞는 점이 없으면) 나눌 수 없어 null로 둔다. 화면에서 회색 칸이다
                    weights[hour] == 0
                            ? null
                            : weighted[hour].divide(
                                    BigDecimal.valueOf(weights[hour]), scale(request.item()), RoundingMode.HALF_UP));
        }
        return result;
    }

    /** 점에서 고른 항목의 값을 꺼낸다. 유입·유출은 네 방향 합이다 */
    private static BigDecimal valueOf(StatSeriesPoint point, HeatmapRequest.Item item) {
        return switch (item) {
            case COUNT -> point.countAvg();
            case DENSITY -> point.density();
            case INFLOW -> sum(point.inflow());
            case OUTFLOW -> sum(point.outflow());
        };
    }

    /** 방향별 인원(N·E·S·W)을 모두 더한다 */
    private static BigDecimal sum(Map<String, Integer> flow) {
        return BigDecimal.valueOf(
                flow.values().stream().mapToLong(Integer::longValue).sum());
    }

    /** 항목별 결과 소수 자릿수. 시간별 통계 칼럼의 자릿수와 맞춘다 */
    private static int scale(HeatmapRequest.Item item) {
        return switch (item) {
            case COUNT -> COUNT_SCALE;
            case DENSITY -> DENSITY_SCALE;
            case INFLOW, OUTFLOW -> FLOW_SCALE;
        };
    }
}
