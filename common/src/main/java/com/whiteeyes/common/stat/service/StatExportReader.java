package com.whiteeyes.common.stat.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
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
 * {@value #ROW_BUDGET}개다(구역 수 × 시각 수). 묶음마다 따로 조회하므로 긴 트랜잭션을 잡지 않는다.
 */
@Service
@RequiredArgsConstructor
public class StatExportReader {

    /** 한 번에 읽을 원본 행 수의 기준. */
    // 메모리를 한 번에 많이 쓰지 않도록 묶음 하나가 읽는 원본 행 수의 상한을 정해 둔 값(구역 수 × 시각 수로 묶음 크기를 정한다)
    static final int ROW_BUDGET = 50_000;

    // 전체(ALL) 범위의 대상 이름(파일 이름·작업 기록에 쓴다)
    private static final String ALL_TARGET_NAME = "전체";
    // 삭제된 구역 이름 뒤에 붙인다(FNC-A-017 업무 규칙, 통계 그래프 화면과 같은 표기)
    private static final String DELETED_SUFFIX = " (삭제됨)";
    // 지역 합산 줄에서 합산한 지역보다 아래 단계 칸에 쓴다(FNC-C-023 처리 순서 3)
    static final String REGION_SUM_LABEL = "(지역 합산)";

    // 이름이 없는 칸은 앞에 둔다
    // 이름 정렬 규칙. null(해당 단계가 없는 칸)을 가장 앞에 두고 나머지는 문자 코드 순서로 정렬한다
    private static final Comparator<String> NAME_ORDER = Comparator.nullsFirst(Comparator.<String>naturalOrder());

    // 지역·구역 조회와 존재 확인(없으면 404)을 맡는다
    private final RegionQueryService regionQueryService;
    // 전체 지역을 한 번에 읽어 구역의 시·구·동 이름을 채울 때 쓴다
    private final RegionRepository regionRepository;
    // 전체(ALL) 범위의 삭제되지 않은 구역을 읽는다
    private final ZoneRepository zoneRepository;
    // 값 계산은 통계 그래프와 같게 하려고 시계열 서비스를 그대로 호출한다
    private final StatSeriesService statSeriesService;

    /**
     * 대상을 확인하고 대상 줄을 정한 뒤 행 수를 확인한다. 전체(ALL)·지역 기본(구역별)은 삭제되지 않은 구역만 줄로 쓴다
     * (FNC-C-023 업무 규칙). 지역 합산(sum)은 통계 그래프처럼 삭제된 구역도 더한다. 구역(ZONE) 대상은 통계 그래프
     * (FNC-C-012)처럼 삭제된 구역도 받고, '구역' 열 이름에 '(삭제됨)'을 붙인다(파일 이름에는 붙이지 않음).
     *
     * @throws com.whiteeyes.common.region.exception.ZoneNotFoundException 없는 구역이면(404)
     * @throws com.whiteeyes.common.region.exception.RegionNotFoundException 없는 지역이면(404)
     * @throws com.whiteeyes.common.stat.exception.TooManyRowsException 한 시트가 100만 행을 넘으면(400)
     */
    @Transactional(readOnly = true)
    public StatExportPlan plan(StatExportRequest request) {
        // 지역 번호 → 지역 엔티티. 구역 줄마다 부모를 거슬러 올라가며 이름을 채우는데, 줄마다 쿼리하지 않으려고 한 번에 읽어 둔다
        Map<Long, Region> regions =
                regionRepository.findAll().stream().collect(Collectors.toMap(Region::getId, Function.identity()));
        return switch (request.scope()) {
            case ZONE -> {
                // 구역 대상은 통계 그래프처럼 삭제된 구역도 허용한다(없으면 404)
                Zone zone = regionQueryService.getZoneIncludingDeleted(request.targetId());
                // 삭제된 구역은 엑셀 '구역' 열에만 '(삭제됨)'을 붙인다. 파일 이름(targetName)에는 붙이지 않는다
                String zoneName = zone.getDeletedAt() == null ? zone.getName() : zone.getName() + DELETED_SUFFIX;
                List<StatExportSeries> series = List.of(named(zone.getId(), zone.getDongId(), zoneName, regions));
                // 줄 1개 × 시각 수가 한도를 넘는지 확인한다. 넘으면 400
                yield new StatExportPlan(request, zone.getName(), series, 1, request.checkRowLimit(1));
            }
            case REGION -> regionPlan(request, regions);
            case ALL -> {
                // 삭제되지 않은 모든 구역이 각각 한 줄이 된다
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
        // 구역별로 줄이 여러 개면(전체·지역 기본) 한 번에 모든 구역을 읽고, 줄이 하나면(구역·지역 합산) 통계 그래프 계산을 기간별로 나눠 부른다
        if (plan.perZone()) {
            readPerZone(plan, sink);
        } else {
            readSingle(plan, sink);
        }
    }

    private StatExportPlan regionPlan(StatExportRequest request, Map<Long, Region> regions) {
        // 지역 대상을 찾는다. 없으면 404
        Region region = regionQueryService.getRegion(request.targetId());
        List<Long> dongIds = regionQueryService.findEmdsUnder(region).stream()
                .map(Region::getId)
                .toList();
        // 합산하지 않으면 지역 아래 삭제되지 않은 구역마다 한 줄이 된다
        if (!request.sum()) {
            List<StatExportSeries> series =
                    dongIds.isEmpty() ? List.of() : zoneSeries(regionQueryService.findZonesInDongs(dongIds), regions);
            return new StatExportPlan(
                    request, region.getName(), series, series.size(), request.checkRowLimit(series.size()));
        }
        // 합산이면 줄은 하나지만 원본은 소속 구역(삭제 포함) 전체를 읽는다. 읽는 묶음 크기를 정하려고 그 수를 센다
        int sourceZoneCount = dongIds.isEmpty()
                ? 0
                : regionQueryService.findAllZonesInDongs(dongIds).size();
        return new StatExportPlan(
                request,
                region.getName(),
                List.of(regionSumSeries(region, regions)),
                sourceZoneCount,
                request.checkRowLimit(1));
    }

    // 합산한 지역보다 아래 단계의 지역 칸과 구역 칸은 비우지 않고 '(지역 합산)'을 쓴다. 위 단계 칸은 그대로 둔다
    private static StatExportSeries regionSumSeries(Region region, Map<Long, Region> regions) {
        // 지역 자신에서 위로 올라가며 시·도·시·군·구·읍·면·동 이름을 채운 줄을 먼저 만든다
        StatExportSeries path = named(null, region.getId(), null, regions);
        RegionLevel level = region.getLevel();
        return new StatExportSeries(
                null,
                path.sido(),
                // 시·도 지역을 합산하면 시·군·구 칸은 하나로 정할 수 없어 '(지역 합산)'을 쓴다. 단계는 enum 선언 순서(SIDO < SIGUNGU < EMD)로 비교한다
                level.compareTo(RegionLevel.SIGUNGU) < 0 ? REGION_SUM_LABEL : path.sigungu(),
                // 읍·면·동 칸도 같은 이유로 시·군·구 이상을 합산하면 '(지역 합산)'이다
                level.compareTo(RegionLevel.EMD) < 0 ? REGION_SUM_LABEL : path.emd(),
                REGION_SUM_LABEL);
    }

    // 구역 줄은 시·도 → 시·군·구 → 읍·면·동 → 구역 이름 가나다순, 이름이 같으면 구역 번호 순(FNC-C-023 처리 순서 2).
    // 한글 음절은 유니코드에서 가나다 순으로 놓여 있어 글자 코드 순서가 곧 가나다순이다
    private List<StatExportSeries> zoneSeries(List<Zone> zones, Map<Long, Region> regions) {
        return zones.stream()
                // 구역마다 시·구·동 이름이 채워진 줄 정보로 바꾼다
                .map(zone -> zoneSeries(zone, regions))
                .sorted(Comparator.comparing(StatExportSeries::sido, NAME_ORDER)
                        .thenComparing(StatExportSeries::sigungu, NAME_ORDER)
                        .thenComparing(StatExportSeries::emd, NAME_ORDER)
                        .thenComparing(StatExportSeries::zoneName, NAME_ORDER)
                        .thenComparing(StatExportSeries::zoneId))
                .toList();
    }

    private StatExportSeries zoneSeries(Zone zone, Map<Long, Region> regions) {
        return named(zone.getId(), zone.getDongId(), zone.getName(), regions);
    }

    // 지역에서 위로 올라가며 시·도, 시·군·구, 읍·면·동 이름을 채운다
    private static StatExportSeries named(Long zoneId, Long regionId, String zoneName, Map<Long, Region> regions) {
        // 단계(시·도/시·군·구/읍·면·동) → 이름. 시작 지역에서 최상위까지 부모를 따라 올라가며 채운다
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

    /** 부모 지역을 이미 읽어 둔 맵에서 찾는다(부모 연관을 지연 로딩하지 않아도 되게). 최상위면 null */
    private static Region parentOf(Region region, Map<Long, Region> regions) {
        return region.getParent() == null
                ? null
                : regions.get(region.getParent().getId());
    }

    // 시각을 잘라 모든 구역을 한 번에 읽고, 시각 순으로 묶어 계획의 줄 순서대로 넘긴다(시각 묶음 정렬, FNC-C-023 처리 순서 2).
    // 한 토막은 약 ROW_BUDGET ÷ 구역 수 개 시각
    private void readPerZone(StatExportPlan plan, BiConsumer<StatExportSeries, StatSeriesPoint> sink) {
        StatExportRequest request = plan.request();
        List<StatExportSeries> series = plan.series();
        if (series.isEmpty()) {
            return;
        }
        // 모든 줄이 구역 줄이므로 구역 번호를 뽑아 한 번에 읽는다
        List<Long> zoneIds = series.stream().map(StatExportSeries::zoneId).toList();
        StatInterval interval = request.interval();
        // 한 토막에 몇 개 시각을 읽을지. 구역 수가 많을수록 토막당 시각 수가 줄어 읽는 행 수가 일정해진다
        int windowSize = windowSize(ROW_BUDGET, series.size());
        // 기간을 토막으로 나눠 토막마다 읽고 바로 쓴다(전체 기간을 메모리에 올리지 않는다)
        for (TimeWindow window : windows(interval, request.from(), request.to(), windowSize)) {
            Map<Long, List<StatSeriesPoint>> pointsByZone =
                    statSeriesService.findZoneSeries(zoneIds, interval, window.from(), window.to());
            // 같은 기간이므로 모든 구역의 점 개수가 같다. 첫 구역 기준으로 시각 수를 센다
            int pointCount = pointsByZone.get(zoneIds.get(0)).size();
            for (int index = 0; index < pointCount; index++) {
                // 같은 시각의 모든 구역 행을 연달아 쓴다(시각 묶음 정렬). 구역 순서는 계획의 줄 순서다
                for (StatExportSeries line : series) {
                    sink.accept(line, pointsByZone.get(line.zoneId()).get(index));
                }
            }
        }
    }

    // 줄 하나(구역·지역 합산)는 기간을 잘라 통계 그래프 계산을 여러 번 부른다. 한 토막은 약 ROW_BUDGET ÷ 원본 구역 수 개 시각
    private void readSingle(StatExportPlan plan, BiConsumer<StatExportSeries, StatSeriesPoint> sink) {
        StatExportRequest request = plan.request();
        // 구역·지역 합산은 줄이 하나뿐이다
        StatExportSeries line = plan.series().get(0);
        StatTargetType targetType =
                request.scope() == StatExportScope.ZONE ? StatTargetType.ZONE : StatTargetType.REGION;
        StatInterval interval = request.interval();
        // 줄은 하나여도 지역 합산이면 소속 구역 전부를 읽으므로 원본 구역 수로 토막 크기를 정한다
        int windowSize = windowSize(ROW_BUDGET, plan.sourceZoneCount());
        for (TimeWindow window : windows(interval, request.from(), request.to(), windowSize)) {
            statSeriesService
                    .findSeries(
                            new StatSeriesRequest(targetType, request.targetId(), interval, window.from(), window.to()))
                    .items()
                    // 점마다 줄 정보와 함께 엑셀 작성기에 넘긴다
                    .forEach(point -> sink.accept(line, point));
        }
    }

    /**
     * 한 번에 읽을 시각 수. 시각마다 rowsPerTime개(구역 수)의 원본 행을 읽으므로 rowBudget ÷ rowsPerTime개이고, 적어도 1개다.
     * rowsPerTime이 0(원본 구역 없음)이면 1로 본다.
     */
    static int windowSize(int rowBudget, int rowsPerTime) {
        // 두 번의 max로 0 나눗셈과 토막 크기 0(무한 반복)을 막는다
        return Math.max(1, rowBudget / Math.max(1, rowsPerTime));
    }

    /**
     * [from, to)를 시각 windowSize개씩 자른 구간. 첫 구간은 from이 속한 단위의 시작부터이고(통계 그래프와 같은 시각),
     * 마지막 구간은 to에서 끝난다.
     */
    static List<TimeWindow> windows(StatInterval interval, LocalDateTime from, LocalDateTime to, int windowSize) {
        // 토막 목록. 첫 토막은 from이 속한 단위의 시작부터라 통계 그래프의 시각과 어긋나지 않는다
        List<TimeWindow> windows = new ArrayList<>();
        // from이 구간 중간이어도 그 구간의 시작에서 출발한다
        LocalDateTime start = interval.floor(from);
        while (start.isBefore(to)) {
            LocalDateTime end = start;
            // 시각 windowSize개만큼 또는 끝까지 앞으로 나아간다
            for (int i = 0; i < windowSize && end.isBefore(to); i++) {
                end = interval.next(end);
            }
            // 마지막 토막은 끝 시각(to)을 넘지 않게 자른다
            windows.add(new TimeWindow(start, end.isBefore(to) ? end : to));
            // 다음 토막은 이 토막이 끝난 곳에서 시작한다
            start = end;
        }
        return windows;
    }

    /** 한 번에 읽는 기간 [from, to). */
    record TimeWindow(LocalDateTime from, LocalDateTime to) {}
}
