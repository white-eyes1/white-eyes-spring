package com.whiteeyes.common.stat.service;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.whiteeyes.common.region.entity.Region;
import com.whiteeyes.common.region.entity.RegionLevel;
import com.whiteeyes.common.region.entity.Zone;
import com.whiteeyes.common.region.repository.RegionRepository;
import com.whiteeyes.common.region.repository.ZoneRepository;
import com.whiteeyes.common.region.service.RegionQueryService;
import com.whiteeyes.common.stat.dto.StatExportPlan;
import com.whiteeyes.common.stat.dto.StatExportRequest;
import com.whiteeyes.common.stat.dto.StatExportScope;
import com.whiteeyes.common.stat.dto.StatExportSeries;
import com.whiteeyes.common.stat.dto.StatInterval;
import com.whiteeyes.common.stat.dto.StatSeriesPoint;
import com.whiteeyes.common.stat.dto.StatSeriesRequest;
import com.whiteeyes.common.stat.dto.StatTargetType;

/**
 * 통계 엑셀에 쓸 값을 읽는다(FNC-C-023 처리 순서 1·2). 값은 통계 그래프(FNC-C-012)와 같은 계산이다.
 *
 * <p>행이 많아도 메모리에 한꺼번에 올리지 않도록 나눠 읽고, 읽은 묶음은 바로 넘긴다. 한 번에 읽는 원본 행은 약
 * {@value #DEFAULT_ROW_BUDGET}개다(구역 수 × 시각 수). 묶음마다 따로 조회하므로 긴 트랜잭션을 잡지 않는다.
 */
@Service
public class StatExportReader {

    /** 한 번에 읽을 원본 행 수의 기준. */
    static final int DEFAULT_ROW_BUDGET = 50_000;

    private static final String ALL_TARGET_NAME = "전체";
    // 삭제된 구역 이름 뒤에 붙인다(FNC-A-017 업무 규칙, 통계 그래프 화면과 같은 표기)
    private static final String DELETED_SUFFIX = " (삭제됨)";
    // 지역 합산 줄에서 합산한 지역보다 아래 단계 칸에 쓴다(FNC-C-023 처리 순서 3)
    static final String REGION_SUM_LABEL = "(지역 합산)";

    private final RegionQueryService regionQueryService;
    private final RegionRepository regionRepository;
    private final ZoneRepository zoneRepository;
    private final StatSeriesService statSeriesService;
    private final int rowBudget;

    @Autowired
    public StatExportReader(
            RegionQueryService regionQueryService,
            RegionRepository regionRepository,
            ZoneRepository zoneRepository,
            StatSeriesService statSeriesService) {
        this(regionQueryService, regionRepository, zoneRepository, statSeriesService, DEFAULT_ROW_BUDGET);
    }

    // 나눠 읽기 시험에서 기준을 작게 줄 때 쓴다
    StatExportReader(
            RegionQueryService regionQueryService,
            RegionRepository regionRepository,
            ZoneRepository zoneRepository,
            StatSeriesService statSeriesService,
            int rowBudget) {
        this.regionQueryService = regionQueryService;
        this.regionRepository = regionRepository;
        this.zoneRepository = zoneRepository;
        this.statSeriesService = statSeriesService;
        this.rowBudget = rowBudget;
    }

    /**
     * 대상을 확인하고 대상 줄을 정한 뒤 행 수를 확인한다. 전체(ALL)·지역 펼침은 삭제되지 않은 구역만 줄로 쓴다
     * (FNC-C-023 업무 규칙). 지역 합산은 통계 그래프처럼 삭제된 구역도 더한다. 구역(ZONE) 대상은 통계 그래프
     * (FNC-C-012)처럼 삭제된 구역도 받고, '구역' 열 이름에 '(삭제됨)'을 붙인다(파일 이름에는 붙이지 않음).
     *
     * @throws com.whiteeyes.common.region.exception.ZoneNotFoundException 없는 구역이면(404)
     * @throws com.whiteeyes.common.region.exception.RegionNotFoundException 없는 지역이면(404)
     * @throws com.whiteeyes.common.stat.exception.TooManyRowsException 한 시트가 100만 행을 넘으면(400)
     */
    @Transactional(readOnly = true)
    public StatExportPlan plan(StatExportRequest request) {
        Map<Long, Region> regions = regionRepository.findAll().stream()
                .collect(Collectors.toMap(Region::getId, Function.identity()));
        return switch (request.scope()) {
            case ZONE -> {
                Zone zone = regionQueryService.getZoneIncludingDeleted(request.targetId());
                String zoneName = zone.getDeletedAt() == null ? zone.getName() : zone.getName() + DELETED_SUFFIX;
                List<StatExportSeries> series = List.of(named(zone.getId(), zone.getDongId(), zoneName, regions));
                yield new StatExportPlan(request, zone.getName(), series, 1, request.checkRowLimit(1));
            }
            case REGION -> regionPlan(request, regions);
            case ALL -> {
                List<StatExportSeries> series = zoneSeries(zoneRepository.findByDeletedAtIsNullOrderById(), regions);
                yield new StatExportPlan(
                        request, ALL_TARGET_NAME, series, series.size(), request.checkRowLimit(series.size()));
            }
        };
    }

    /**
     * 대상 줄마다 시각 순으로 점을 넘긴다(줄 순서는 계획의 series 순서). 점은 통계 그래프의 점과 같고,
     * 통계가 없는 시각은 값이 null인 점이다.
     */
    public void read(StatExportPlan plan, BiConsumer<StatExportSeries, StatSeriesPoint> sink) {
        if (plan.perZone()) {
            readPerZone(plan, sink);
        } else {
            readSingle(plan, sink);
        }
    }

    private StatExportPlan regionPlan(StatExportRequest request, Map<Long, Region> regions) {
        Region region = regionQueryService.getRegion(request.targetId());
        List<Long> dongIds = regionQueryService.findEmdsUnder(region).stream().map(Region::getId).toList();
        if (request.expand()) {
            List<StatExportSeries> series = dongIds.isEmpty()
                    ? List.of()
                    : zoneSeries(regionQueryService.findZonesInDongs(dongIds), regions);
            return new StatExportPlan(
                    request, region.getName(), series, series.size(), request.checkRowLimit(series.size()));
        }
        int sourceZoneCount = dongIds.isEmpty() ? 0 : regionQueryService.findAllZonesInDongs(dongIds).size();
        return new StatExportPlan(
                request,
                region.getName(),
                List.of(regionSumSeries(region, regions)),
                sourceZoneCount,
                request.checkRowLimit(1));
    }

    // 합산한 지역보다 아래 단계의 지역 칸과 구역 칸은 비우지 않고 '(지역 합산)'을 쓴다. 위 단계 칸은 그대로 둔다
    private static StatExportSeries regionSumSeries(Region region, Map<Long, Region> regions) {
        StatExportSeries path = named(null, region.getId(), null, regions);
        RegionLevel level = region.getLevel();
        return new StatExportSeries(
                null,
                path.sido(),
                level.compareTo(RegionLevel.SIGUNGU) < 0 ? REGION_SUM_LABEL : path.sigungu(),
                level.compareTo(RegionLevel.EMD) < 0 ? REGION_SUM_LABEL : path.emd(),
                REGION_SUM_LABEL);
    }

    // 구역 줄은 읍·면·동 코드 순, 같은 동 안에서는 구역 번호 순
    private List<StatExportSeries> zoneSeries(List<Zone> zones, Map<Long, Region> regions) {
        return zones.stream()
                .sorted(Comparator.comparing((Zone zone) -> regions.get(zone.getDongId()).getCode())
                        .thenComparing(Zone::getId))
                .map(zone -> zoneSeries(zone, regions))
                .toList();
    }

    private StatExportSeries zoneSeries(Zone zone, Map<Long, Region> regions) {
        return named(zone.getId(), zone.getDongId(), zone.getName(), regions);
    }

    // 지역에서 위로 올라가며 시·도, 시·군·구, 읍·면·동 이름을 채운다
    private static StatExportSeries named(Long zoneId, Long regionId, String zoneName, Map<Long, Region> regions) {
        Map<RegionLevel, String> names = new EnumMap<>(RegionLevel.class);
        for (Region region = regions.get(regionId); region != null; region = parentOf(region, regions)) {
            names.put(region.getLevel(), region.getName());
        }
        return new StatExportSeries(
                zoneId,
                names.get(RegionLevel.SIDO),
                names.get(RegionLevel.SIGUNGU),
                names.get(RegionLevel.EMD),
                zoneName);
    }

    private static Region parentOf(Region region, Map<Long, Region> regions) {
        return region.getParent() == null ? null : regions.get(region.getParent().getId());
    }

    // 구역 묶음마다 원본을 한 번 읽는다. 한 묶음은 약 rowBudget ÷ 시각 수 개 구역
    private void readPerZone(StatExportPlan plan, BiConsumer<StatExportSeries, StatSeriesPoint> sink) {
        StatExportRequest request = plan.request();
        int chunkSize = (int) Math.max(1, rowBudget / request.pointCount());
        List<StatExportSeries> series = plan.series();
        for (int start = 0; start < series.size(); start += chunkSize) {
            List<StatExportSeries> chunk = series.subList(start, Math.min(start + chunkSize, series.size()));
            Map<Long, List<StatSeriesPoint>> pointsByZone = statSeriesService.findZoneSeries(
                    chunk.stream().map(StatExportSeries::zoneId).toList(),
                    request.interval(),
                    request.from(),
                    request.to());
            for (StatExportSeries line : chunk) {
                pointsByZone.get(line.zoneId()).forEach(point -> sink.accept(line, point));
            }
        }
    }

    // 줄 하나(구역·지역 합산)는 기간을 잘라 통계 그래프 계산을 여러 번 부른다. 한 토막은 약 rowBudget ÷ 원본 구역 수 개 시각
    private void readSingle(StatExportPlan plan, BiConsumer<StatExportSeries, StatSeriesPoint> sink) {
        StatExportRequest request = plan.request();
        StatExportSeries line = plan.series().get(0);
        StatTargetType targetType =
                request.scope() == StatExportScope.ZONE ? StatTargetType.ZONE : StatTargetType.REGION;
        StatInterval interval = request.interval();
        int windowSize = Math.max(1, rowBudget / Math.max(1, plan.sourceZoneCount()));
        LocalDateTime start = interval.floor(request.from());
        while (start.isBefore(request.to())) {
            LocalDateTime end = start;
            for (int i = 0; i < windowSize && end.isBefore(request.to()); i++) {
                end = interval.next(end);
            }
            LocalDateTime windowTo = end.isBefore(request.to()) ? end : request.to();
            statSeriesService
                    .findSeries(new StatSeriesRequest(targetType, request.targetId(), interval, start, windowTo))
                    .items()
                    .forEach(point -> sink.accept(line, point));
            start = end;
        }
    }
}
