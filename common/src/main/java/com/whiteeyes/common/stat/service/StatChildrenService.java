package com.whiteeyes.common.stat.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.whiteeyes.common.region.entity.Region;
import com.whiteeyes.common.region.entity.RegionLevel;
import com.whiteeyes.common.region.entity.Zone;
import com.whiteeyes.common.region.service.RegionQueryService;
import com.whiteeyes.common.stat.dto.StatChildrenRequest;
import com.whiteeyes.common.stat.dto.StatChildrenResponse;
import com.whiteeyes.common.stat.dto.StatExportItem;
import com.whiteeyes.common.stat.dto.StatSeriesPoint;
import com.whiteeyes.common.stat.dto.StatSeriesRequest;
import com.whiteeyes.common.stat.dto.StatTargetType;

/**
 * 여러 지역 비교(FNC-C-014). 상위 지역의 하위 단위(시·도 → 시·군·구 → 읍·면·동 → 구역)마다 통계 그래프(FNC-C-012)와 같은
 * 방식으로 계열을 내고, 고른 항목 값만 돌려준다.
 *
 * <p>하위 단위가 10개를 넘으면 기간의 인원 평균이 큰 10개만 돌려주고 나머지 수를 omitted로 알린다(업무 규칙).
 * 순서는 하위 단위 목록 순서(지역은 코드 순, 구역은 번호 순)를 그대로 둔다(그래프 색이 순위에 따라 바뀌지 않게).
 */
@Service
public class StatChildrenService {

    /** 한 번에 겹쳐 그리는 최대 하위 단위 수(FNC-C-014 처리 순서 1). */
    public static final int MAX_SERIES = 10;

    // 순위 비교에만 쓰는 평균의 자릿수
    private static final int AVERAGE_SCALE = 6;

    private final RegionQueryService regionQueryService;
    private final StatSeriesService statSeriesService;

    public StatChildrenService(RegionQueryService regionQueryService, StatSeriesService statSeriesService) {
        this.regionQueryService = regionQueryService;
        this.statSeriesService = statSeriesService;
    }

    /**
     * @throws com.whiteeyes.common.region.exception.RegionNotFoundException 없는 지역이면(404)
     */
    @Transactional(readOnly = true)
    public StatChildrenResponse findChildren(StatChildrenRequest request) {
        Region region = regionQueryService.getRegion(request.regionId());
        List<ChildSeries> children = region.getLevel() == RegionLevel.EMD
                ? zoneChildren(region, request)
                : regionChildren(region, request);

        Set<Long> shown = topByCountAvg(children);
        List<StatChildrenResponse.Series> series = children.stream()
                .filter(child -> shown.contains(child.id()))
                .map(child -> toSeries(child, request.item()))
                .toList();
        return new StatChildrenResponse(series, children.size() - series.size());
    }

    // 읍·면·동 아래는 구역. 목록·지도처럼 삭제되지 않은 구역만 하위 단위로 본다(통계 엑셀 '구역별 펼치기'와 같은 규칙)
    private List<ChildSeries> zoneChildren(Region dong, StatChildrenRequest request) {
        List<Zone> zones = regionQueryService.findZonesInDongs(List.of(dong.getId())).stream()
                .sorted(Comparator.comparing(Zone::getId))
                .toList();
        if (zones.isEmpty()) {
            return List.of();
        }
        Map<Long, List<StatSeriesPoint>> pointsByZone = statSeriesService.findZoneSeries(
                zones.stream().map(Zone::getId).toList(), request.interval(), request.from(), request.to());
        return zones.stream()
                .map(zone -> new ChildSeries(zone.getId(), zone.getName(), pointsByZone.get(zone.getId())))
                .toList();
    }

    // 하위 지역은 지역 대상 그래프와 같은 계산(소속 구역 합산, 지역 안 이동 제외)
    private List<ChildSeries> regionChildren(Region region, StatChildrenRequest request) {
        List<ChildSeries> result = new ArrayList<>();
        for (Region child : regionQueryService.findChildRegions(region.getId())) {
            List<StatSeriesPoint> points = statSeriesService
                    .findSeries(new StatSeriesRequest(
                            StatTargetType.REGION, child.getId(), request.interval(), request.from(), request.to()))
                    .items();
            result.add(new ChildSeries(child.getId(), child.getName(), points));
        }
        return result;
    }

    // 인원 평균(값이 있는 점의 countAvg 평균)이 큰 순서로 10개. 통계가 전혀 없는 단위는 맨 뒤, 같으면 목록 순서
    private static Set<Long> topByCountAvg(List<ChildSeries> children) {
        if (children.size() <= MAX_SERIES) {
            return new HashSet<>(children.stream().map(ChildSeries::id).toList());
        }
        Comparator<ChildSeries> byAverageDesc = Comparator.comparing(
                ChildSeries::countAverage, Comparator.nullsLast(Comparator.<BigDecimal>reverseOrder()));
        return new HashSet<>(children.stream()
                .sorted(byAverageDesc)
                .limit(MAX_SERIES)
                .map(ChildSeries::id)
                .toList());
    }

    private static StatChildrenResponse.Series toSeries(ChildSeries child, StatExportItem item) {
        List<StatChildrenResponse.Point> points = child.points().stream()
                .map(point -> new StatChildrenResponse.Point(point.at(), valueOf(point, item)))
                .toList();
        return new StatChildrenResponse.Series(child.id(), child.name(), points);
    }

    // 통계 그래프 화면의 항목 값과 같다: 유입·유출은 네 방향 합. 값이 없는 점은 null
    private static BigDecimal valueOf(StatSeriesPoint point, StatExportItem item) {
        return switch (item) {
            case COUNT -> point.countAvg();
            case DENSITY -> point.density();
            case INFLOW -> sum(point.inflow());
            case OUTFLOW -> sum(point.outflow());
            case DWELL -> point.dwellAvgSec();
        };
    }

    private static BigDecimal sum(Map<String, Integer> flow) {
        return flow == null
                ? null
                : BigDecimal.valueOf(flow.values().stream().mapToLong(Integer::longValue).sum());
    }

    /** 하위 단위 하나와 그 계열(통계 그래프의 점). */
    private record ChildSeries(long id, String name, List<StatSeriesPoint> points) {

        // 값이 있는 점이 없으면 null
        BigDecimal countAverage() {
            List<BigDecimal> values = points.stream()
                    .map(StatSeriesPoint::countAvg)
                    .filter(Objects::nonNull)
                    .toList();
            if (values.isEmpty()) {
                return null;
            }
            BigDecimal total = values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            return total.divide(BigDecimal.valueOf(values.size()), AVERAGE_SCALE, RoundingMode.HALF_UP);
        }
    }
}
