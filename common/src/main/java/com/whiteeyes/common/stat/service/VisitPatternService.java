package com.whiteeyes.common.stat.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.whiteeyes.common.region.entity.Region;
import com.whiteeyes.common.region.service.RegionQueryService;
import com.whiteeyes.common.stat.dto.DashboardTargetType;
import com.whiteeyes.common.stat.dto.VisitPatternRequest;
import com.whiteeyes.common.stat.dto.VisitPatternResponse;
import com.whiteeyes.common.stat.repository.ZoneStatHourlyRepository;
import com.whiteeyes.common.stat.repository.ZoneStatHourlyRepository.HourlyCountSum;

/**
 * 방문 패턴(FNC-C-028). 기간의 시간별 인원(지역은 같은 시각 소속 구역의 합)을 요일 × 시간 칸마다 평균하고,
 * 평일(월~금)·주말(토·일) 행을 따로 평균한다. 받은 초가 있는 시간만 넣는다(데이터 있는 시간만).
 */
@Service
public class VisitPatternService {

    private static final int DAYS = 7;
    private static final int HOURS = 24;
    // 평균 인원은 소수 첫째 자리까지(api/public.md 예시 88.1, 기간 비교의 증감률과 같은 자릿수)
    private static final int RESULT_SCALE = 1;

    private final RegionQueryService regionQueryService;
    private final ZoneStatHourlyRepository zoneStatHourlyRepository;

    public VisitPatternService(
            RegionQueryService regionQueryService, ZoneStatHourlyRepository zoneStatHourlyRepository) {
        this.regionQueryService = regionQueryService;
        this.zoneStatHourlyRepository = zoneStatHourlyRepository;
    }

    /**
     * @throws com.whiteeyes.common.region.exception.RegionNotFoundException 없는 지역이면(404)
     * @throws com.whiteeyes.common.region.exception.ZoneNotFoundException 없거나 삭제된 구역이면(404)
     */
    @Transactional(readOnly = true)
    public VisitPatternResponse findPattern(VisitPatternRequest request) {
        List<Long> zoneIds = findZoneIds(request.targetType(), request.targetId());
        List<HourlyCountSum> hours = zoneIds.isEmpty()
                ? List.of()
                : zoneStatHourlyRepository.sumCountAvgByStatAt(
                        zoneIds, request.period().startAt(), request.period().endAt());

        Average[] cells = newAverages(DAYS * HOURS);
        Average[] weekday = newAverages(HOURS);
        Average[] weekend = newAverages(HOURS);
        for (HourlyCountSum hour : hours) {
            LocalDateTime at = hour.getStatAt();
            DayOfWeek day = at.getDayOfWeek();
            cells[(day.getValue() - 1) * HOURS + at.getHour()].add(hour.getCountSum());
            boolean isWeekend = day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY;
            (isWeekend ? weekend : weekday)[at.getHour()].add(hour.getCountSum());
        }

        List<VisitPatternResponse.Cell> cellList = new ArrayList<>(DAYS * HOURS);
        for (int index = 0; index < DAYS * HOURS; index++) {
            cellList.add(new VisitPatternResponse.Cell(index / HOURS + 1, index % HOURS, cells[index].value()));
        }
        return new VisitPatternResponse(cellList, values(weekday), values(weekend));
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

    private static Average[] newAverages(int size) {
        Average[] averages = new Average[size];
        for (int i = 0; i < size; i++) {
            averages[i] = new Average();
        }
        return averages;
    }

    private static List<BigDecimal> values(Average[] averages) {
        List<BigDecimal> result = new ArrayList<>(averages.length);
        for (Average average : averages) {
            result.add(average.value());
        }
        return result;
    }

    /** 칸 하나의 합·개수. 값이 하나도 없으면 평균은 null. */
    private static final class Average {

        private BigDecimal sum = BigDecimal.ZERO;
        private int count;

        void add(BigDecimal value) {
            sum = sum.add(value);
            count++;
        }

        BigDecimal value() {
            return count == 0 ? null : sum.divide(BigDecimal.valueOf(count), RESULT_SCALE, RoundingMode.HALF_UP);
        }
    }
}
