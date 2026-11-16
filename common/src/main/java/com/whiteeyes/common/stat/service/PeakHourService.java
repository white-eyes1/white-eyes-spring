package com.whiteeyes.common.stat.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import lombok.RequiredArgsConstructor;
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
@RequiredArgsConstructor
public class PeakHourService {

    // 하루의 시(0~23) 칸 수
    private static final int HOURS = 24;
    // 돌려줄 피크 시간대 수
    private static final int TOP = 3;
    // 평균 인원은 소수 첫째 자리까지(api/public.md 예시 240.2)
    private static final int RESULT_SCALE = 1;
    // 순위를 정할 때만 쓰는 자릿수(반올림 때문에 순위가 뒤바뀌지 않게)
    private static final int WORK_SCALE = 6;

    // 지역 → 구역 목록을 찾을 때 쓴다(region 도메인의 공개 서비스)
    private final RegionQueryService regionQueryService;
    // 시간별 통계. 기간이 최대 1년(364일)이라 1분 통계 대신 시간별을 읽는다
    private final ZoneStatHourlyRepository zoneStatHourlyRepository;

    /**
     * @param today 오늘(KST). 기간은 어제까지
     * @return 1~3위. 데이터가 없으면 빈 목록, 데이터가 있는 시간대가 3개보다 적으면 그만큼만
     * @throws com.whiteeyes.common.region.exception.RegionNotFoundException 없는 지역이면(404)
     * @throws com.whiteeyes.common.region.exception.ZoneNotFoundException 없거나 삭제된 구역이면(404)
     */
    @Transactional(readOnly = true)
    public List<PeakHourItem> findPeakHours(
            DashboardTargetType targetType, long targetId, PeakPeriod period, LocalDate today) {
        // 대상(구역 또는 지역)을 통계를 읽을 구역 번호 목록으로 바꾼다. 없는 대상이면 여기서 404
        List<Long> zoneIds = findZoneIds(targetType, targetId);
        // 구역이 없는 지역이면 통계도 없다. 빈 IN 쿼리를 피하고 빈 목록으로 끝낸다
        if (zoneIds.isEmpty()) {
            return List.of();
        }
        // 어제까지 days일: 시작은 오늘에서 days일 전, 끝은 오늘 0시(미포함)이다. 예) WEEK는 오늘 7일 전부터 어제까지
        LocalDate from = today.minusDays(period.getDays());
        // 같은 시각 구역들의 평균 인원 합(지역이면 구역 합산)을 시각별로 받는다. 받은 초가 없는 행은 쿼리에서 이미 빠진다
        List<HourlyCountSum> hours =
                zoneStatHourlyRepository.sumCountAvgByStatAt(zoneIds, from.atStartOfDay(), today.atStartOfDay());

        // 시(0~23)별로 인원 합과 건수를 모아 평균을 낸다. 날짜는 구분하지 않고 같은 시끼리 묶는다
        BigDecimal[] sums = new BigDecimal[HOURS];
        int[] counts = new int[HOURS];
        for (HourlyCountSum hour : hours) {
            int index = hour.getStatAt().getHour();
            // 처음 들어오는 값이면 그대로 두고, 아니면 더한다
            sums[index] = sums[index] == null ? hour.getCountSum() : sums[index].add(hour.getCountSum());
            counts[index]++;
        }
        // 데이터가 있는 시간대만 평균을 만든다(없는 시는 순위 대상에서 제외)
        List<HourAverage> averages = new ArrayList<>();
        for (int hour = 0; hour < HOURS; hour++) {
            if (counts[hour] > 0) {
                averages.add(new HourAverage(
                        // 순위 비교용 평균은 넉넉한 자릿수로 구한다(소수 첫째 자리로 먼저 반올림하면 순위가 바뀔 수 있다)
                        hour, sums[hour].divide(BigDecimal.valueOf(counts[hour]), WORK_SCALE, RoundingMode.HALF_UP)));
            }
        }
        // 평균 내림차순, 같으면 이른 시각(처리 순서 3)
        List<HourAverage> top = averages.stream()
                // 평균이 큰 순서, 같으면 이른 시각이 앞이다
                .sorted(Comparator.comparing(HourAverage::average).reversed().thenComparingInt(HourAverage::hour))
                // 상위 3개만 남긴다(시간대가 3개보다 적으면 있는 만큼만)
                .limit(TOP)
                .toList();
        List<PeakHourItem> result = new ArrayList<>(top.size());
        for (int i = 0; i < top.size(); i++) {
            HourAverage item = top.get(i);
            result.add(
                    // 순위는 목록 순서(1부터), 평균은 응답용으로 소수 첫째 자리까지 반올림한다
                    new PeakHourItem(i + 1, item.hour(), item.average().setScale(RESULT_SCALE, RoundingMode.HALF_UP)));
        }
        return result;
    }

    // 기간 비교(FNC-C-020)와 같은 기준: 구역은 삭제되지 않은 것만 대상, 지역은 지난 기간도 보므로 삭제된 구역까지 합산한다
    private List<Long> findZoneIds(DashboardTargetType targetType, long targetId) {
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

    /** 한 시간대(0~23시)의 기간 평균 인원. */
    // 정렬·선택 중에 시와 평균을 같이 들고 다니는 내부용 값 묶음
    private record HourAverage(int hour, BigDecimal average) {}
}
