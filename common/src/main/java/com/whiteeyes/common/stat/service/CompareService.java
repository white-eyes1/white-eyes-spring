package com.whiteeyes.common.stat.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.whiteeyes.common.region.entity.Region;
import com.whiteeyes.common.region.service.RegionQueryService;
import com.whiteeyes.common.stat.dto.CompareRequest;
import com.whiteeyes.common.stat.dto.CompareResponse;
import com.whiteeyes.common.stat.dto.ComparePeriod;
import com.whiteeyes.common.stat.dto.DashboardTargetType;
import com.whiteeyes.common.stat.repository.ZoneStatHourlyRepository;
import com.whiteeyes.common.stat.repository.ZoneStatHourlyRepository.HourlyCountSum;

/**
 * 기간 비교 분석(FNC-C-020). 대상 기간과 기준 기간을 같은 요일·시간대끼리 비교한다.
 * 기간이 최대 1년이라 시간별 통계(zone_stat_hourly)를 원본으로 쓴다.
 */
@Service
public class CompareService {

    private final RegionQueryService regionQueryService;
    private final ZoneStatHourlyRepository zoneStatHourlyRepository;

    public CompareService(RegionQueryService regionQueryService, ZoneStatHourlyRepository zoneStatHourlyRepository) {
        this.regionQueryService = regionQueryService;
        this.zoneStatHourlyRepository = zoneStatHourlyRepository;
    }

    /**
     * @throws com.whiteeyes.common.region.exception.RegionNotFoundException 없는 지역이면(404)
     * @throws com.whiteeyes.common.region.exception.ZoneNotFoundException 없거나 삭제된 구역이면(404)
     */
    @Transactional(readOnly = true)
    public CompareResponse compare(CompareRequest request) {
        List<Long> zoneIds = findZoneIds(request.targetType(), request.targetId());
        return CompareCalculator.compare(
                sumByHour(zoneIds, request.target()), sumByHour(zoneIds, request.base()), request.thresholdPct());
    }

    // 지난 기간도 비교하므로 지역은 삭제된 구역까지 합산한다(FNC-C-016 오늘 기록과 같은 기준)
    private List<Long> findZoneIds(DashboardTargetType targetType, long targetId) {
        if (targetType == DashboardTargetType.ZONE) {
            return List.of(regionQueryService.getActiveZone(targetId).getId());
        }
        Region region = regionQueryService.getRegion(targetId);
        List<Long> dongIds = regionQueryService.findEmdsUnder(region).stream().map(Region::getId).toList();
        return dongIds.isEmpty() ? List.of() : regionQueryService.findAllZoneIdsInDongs(dongIds);
    }

    private Map<LocalDateTime, BigDecimal> sumByHour(List<Long> zoneIds, ComparePeriod period) {
        if (zoneIds.isEmpty()) {
            return Map.of();
        }
        return zoneStatHourlyRepository.sumCountAvgByStatAt(zoneIds, period.startAt(), period.endAt()).stream()
                .collect(Collectors.toMap(HourlyCountSum::getStatAt, HourlyCountSum::getCountSum));
    }
}
