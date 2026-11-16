package com.whiteeyes.common.stat.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.whiteeyes.common.region.entity.Region;
import com.whiteeyes.common.region.service.RegionQueryService;
import com.whiteeyes.common.stat.dto.ComparePeriod;
import com.whiteeyes.common.stat.dto.CompareRequest;
import com.whiteeyes.common.stat.dto.CompareResponse;
import com.whiteeyes.common.stat.dto.DashboardTargetType;
import com.whiteeyes.common.stat.repository.ZoneStatHourlyRepository;
import com.whiteeyes.common.stat.repository.ZoneStatHourlyRepository.HourlyCountSum;

/**
 * 기간 비교 분석(FNC-C-020). 대상 기간과 기준 기간을 같은 요일·시간대끼리 비교한다.
 * 기간이 최대 1년이라 시간별 통계(zone_stat_hourly)를 원본으로 쓴다.
 */
@Service
@RequiredArgsConstructor
public class CompareService {

    // 지역 → 구역 목록을 찾을 때 쓴다(region 도메인의 공개 서비스)
    private final RegionQueryService regionQueryService;
    // 비교 원본인 시간별 통계. 기간이 최대 1년이라 1분 통계는 너무 많아 시간별을 쓴다
    private final ZoneStatHourlyRepository zoneStatHourlyRepository;

    /**
     * @throws com.whiteeyes.common.region.exception.RegionNotFoundException 없는 지역이면(404)
     * @throws com.whiteeyes.common.region.exception.ZoneNotFoundException 없거나 삭제된 구역이면(404)
     */
    @Transactional(readOnly = true)
    public CompareResponse compare(CompareRequest request) {
        // 대상(구역 하나 또는 지역)을 통계를 읽을 구역 번호 목록으로 바꾼다. 없는 대상이면 여기서 404
        List<Long> zoneIds = findZoneIds(request.targetType(), request.targetId());
        // 대상·기준 기간 각각 시각별 인원 합을 만들어 계산기에 넘긴다. 계산은 순수 함수라 DB 없이 검증할 수 있다
        return CompareCalculator.compare(
                sumByHour(zoneIds, request.target()), sumByHour(zoneIds, request.base()), request.thresholdPct());
    }

    // 지난 기간도 비교하므로 지역은 삭제된 구역까지 합산한다(FNC-C-016 오늘 기록과 같은 기준)
    private List<Long> findZoneIds(DashboardTargetType targetType, long targetId) {
        // 구역이면 삭제되지 않은 구역인지 확인하고 그 하나만 쓴다(없거나 삭제됐으면 404)
        if (targetType == DashboardTargetType.ZONE) {
            return List.of(regionQueryService.getActiveZone(targetId).getId());
        }
        // 지역이면 그 아래 읍·면·동을 모두 찾고, 거기 속한 구역(삭제된 것 포함)을 합산 대상으로 삼는다
        Region region = regionQueryService.getRegion(targetId);
        List<Long> dongIds = regionQueryService.findEmdsUnder(region).stream()
                .map(Region::getId)
                .toList();
        // 읍·면·동이 없으면 IN 쿼리를 보내지 않고 빈 목록으로 끝낸다
        return dongIds.isEmpty() ? List.of() : regionQueryService.findAllZoneIdsInDongs(dongIds);
    }

    private Map<LocalDateTime, BigDecimal> sumByHour(List<Long> zoneIds, ComparePeriod period) {
        // 구역이 없으면 통계도 없다. 빈 IN 쿼리를 피한다
        if (zoneIds.isEmpty()) {
            return Map.of();
        }
        // 같은 시각의 구역 평균 인원을 DB에서 합산해 온다(구역 수 × 시간 수만큼 행을 가져오지 않는다)
        return zoneStatHourlyRepository.sumCountAvgByStatAt(zoneIds, period.startAt(), period.endAt()).stream()
                // 시각 → 그 시각 인원 합. 쿼리가 시각별로 묶어서 오므로 키가 겹치지 않는다
                .collect(Collectors.toMap(HourlyCountSum::getStatAt, HourlyCountSum::getCountSum));
    }
}
