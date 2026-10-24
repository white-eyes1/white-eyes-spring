package com.whiteeyes.common.stat.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.whiteeyes.common.region.entity.Region;
import com.whiteeyes.common.region.service.RegionQueryService;
import com.whiteeyes.common.stat.dto.DashboardTargetType;
import com.whiteeyes.common.stat.dto.PeakHourItem;
import com.whiteeyes.common.stat.dto.PeakPeriod;
import com.whiteeyes.common.stat.repository.ZoneStatHourlyRepository;
import com.whiteeyes.common.stat.repository.ZoneStatHourlyRepository.HourlyCountSum;

/**
 * 피크 타임 TOP 3(FNC-C-029). 기간의 시간별 인원(지역은 같은 시각 소속 구역의 합)을 시(0~23)별로 평균해
 * 평균이 큰 시간대 3개를 고른다. 같으면 이른 시각이 먼저다. 받은 초가 있는 시간만 넣는다.
 */
@Service
public class PeakHourService {

    private static final int HOURS = 24;
    private static final int TOP = 3;
    // 평균 인원은 소수 첫째 자리까지(api/public.md 예시 240.2)
    private static final int RESULT_SCALE = 1;
    // 순위를 정할 때만 쓰는 자릿수(반올림 때문에 순위가 뒤바뀌지 않게)
    private static final int WORK_SCALE = 6;

    private final RegionQueryService regionQueryService;
    private final ZoneStatHourlyRepository zoneStatHourlyRepository;

    public PeakHourService(RegionQueryService regionQueryService, ZoneStatHourlyRepository zoneStatHourlyRepository) {
        this.regionQueryService = regionQueryService;
        this.zoneStatHourlyRepository = zoneStatHourlyRepository;
    }

    /**
     * @param today 오늘(KST). 기간은 어제까지
     * @return 1~3위. 데이터가 없으면 빈 목록, 데이터가 있는 시간대가 3개보다 적으면 그만큼만
     * @throws com.whiteeyes.common.region.exception.RegionNotFoundException 없는 지역이면(404)
     * @throws com.whiteeyes.common.region.exception.ZoneNotFoundException 없거나 삭제된 구역이면(404)
     */
    @Transactional(readOnly = true)
    public List<PeakHourItem> findPeakHours(
            DashboardTargetType targetType, long targetId, PeakPeriod period, LocalDate today) {
        List<Long> zoneIds = findZoneIds(targetType, targetId);
        if (zoneIds.isEmpty()) {
            return List.of();
        }
        LocalDate from = today.minusDays(period.getDays());
        List<HourlyCountSum> hours =
                zoneStatHourlyRepository.sumCountAvgByStatAt(zoneIds, from.atStartOfDay(), today.atStartOfDay());

        BigDecimal[] sums = new BigDecimal[HOURS];
        int[] counts = new int[HOURS];
        for (HourlyCountSum hour : hours) {
            int index = hour.getStatAt().getHour();
            sums[index] = sums[index] == null ? hour.getCountSum() : sums[index].add(hour.getCountSum());
            counts[index]++;
        }
        List<HourAverage> averages = new ArrayList<>();
        for (int hour = 0; hour < HOURS; hour++) {
            if (counts[hour] > 0) {
                averages.add(new HourAverage(
                        hour, sums[hour].divide(BigDecimal.valueOf(counts[hour]), WORK_SCALE, RoundingMode.HALF_UP)));
            }
        }
        // 평균 내림차순, 같으면 이른 시각(처리 순서 3)
        List<HourAverage> top = averages.stream()
                .sorted(Comparator.comparing(HourAverage::average).reversed().thenComparingInt(HourAverage::hour))
                .limit(TOP)
                .toList();
        List<PeakHourItem> result = new ArrayList<>(top.size());
        for (int i = 0; i < top.size(); i++) {
            HourAverage item = top.get(i);
            result.add(new PeakHourItem(i + 1, item.hour(), item.average().setScale(RESULT_SCALE, RoundingMode.HALF_UP)));
        }
        return result;
    }

    // 기간 비교(FNC-C-020)와 같은 기준: 구역은 삭제되지 않은 것만 대상, 지역은 지난 기간도 보므로 삭제된 구역까지 합산한다
    private List<Long> findZoneIds(DashboardTargetType targetType, long targetId) {
        if (targetType == DashboardTargetType.ZONE) {
            return List.of(regionQueryService.getActiveZone(targetId).getId());
        }
        Region region = regionQueryService.getRegion(targetId);
        List<Long> dongIds = regionQueryService.findEmdsUnder(region).stream().map(Region::getId).toList();
        return dongIds.isEmpty() ? List.of() : regionQueryService.findAllZoneIdsInDongs(dongIds);
    }

    /** 한 시간대(0~23시)의 기간 평균 인원. */
    private record HourAverage(int hour, BigDecimal average) {
    }
}
