package com.whiteeyes.common.stat.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import lombok.RequiredArgsConstructor;
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
@RequiredArgsConstructor
public class VisitPatternService {

    // 한 주의 요일 수
    private static final int DAYS = 7;
    // 하루의 시(0~23) 칸 수
    private static final int HOURS = 24;
    // 평균 인원은 소수 첫째 자리까지(api/public.md 예시 88.1, 기간 비교의 증감률과 같은 자릿수)
    private static final int RESULT_SCALE = 1;

    // 지역 → 구역 목록을 찾을 때 쓴다(region 도메인의 공개 서비스)
    private final RegionQueryService regionQueryService;
    // 시간별 통계. 기간이 최대 1년이라 1분 통계 대신 시간별을 읽는다
    private final ZoneStatHourlyRepository zoneStatHourlyRepository;

    /**
     * @throws com.whiteeyes.common.region.exception.RegionNotFoundException 없는 지역이면(404)
     * @throws com.whiteeyes.common.region.exception.ZoneNotFoundException 없거나 삭제된 구역이면(404)
     */
    @Transactional(readOnly = true)
    public VisitPatternResponse findPattern(VisitPatternRequest request) {
        // 대상을 통계를 읽을 구역 번호 목록으로 바꾼다. 없는 대상이면 여기서 404
        List<Long> zoneIds = findZoneIds(request.targetType(), request.targetId());
        // 같은 시각 구역들의 평균 인원 합(지역이면 구역 합산)을 시각별로 받는다. 구역이 없으면 조회하지 않는다
        List<HourlyCountSum> hours = zoneIds.isEmpty()
                ? List.of()
                : zoneStatHourlyRepository.sumCountAvgByStatAt(
                        zoneIds, request.period().startAt(), request.period().endAt());

        // 요일×시간 168칸 평균, 평일 24칸 평균, 주말 24칸 평균을 따로 쌓는다
        Average[] cells = newAverages(DAYS * HOURS);
        Average[] weekday = newAverages(HOURS);
        Average[] weekend = newAverages(HOURS);
        for (HourlyCountSum hour : hours) {
            LocalDateTime at = hour.getStatAt();
            DayOfWeek day = at.getDayOfWeek();
            // 칸 번호 = (요일 - 1) × 24 + 시. 월요일 0시가 0번이다
            cells[(day.getValue() - 1) * HOURS + at.getHour()].add(hour.getCountSum());
            // 토·일만 주말로 본다(공휴일은 고려하지 않는다)
            boolean isWeekend = day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY;
            // 같은 시각을 평일 또는 주말 시간대 평균에도 함께 쌓는다
            (isWeekend ? weekend : weekday)[at.getHour()].add(hour.getCountSum());
        }

        List<VisitPatternResponse.Cell> cellList = new ArrayList<>(DAYS * HOURS);
        for (int index = 0; index < DAYS * HOURS; index++) {
            // 칸 번호에서 요일(1=월~7=일)과 시를 거꾸로 계산한다. 데이터 없는 칸은 평균이 null이다
            cellList.add(new VisitPatternResponse.Cell(index / HOURS + 1, index % HOURS, cells[index].value()));
        }
        return new VisitPatternResponse(cellList, values(weekday), values(weekend));
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

    /** 값을 쌓을 빈 평균 계산기를 size개 만든다 */
    private static Average[] newAverages(int size) {
        Average[] averages = new Average[size];
        for (int i = 0; i < size; i++) {
            averages[i] = new Average();
        }
        return averages;
    }

    /** 칸별 평균 계산기를 평균값 목록으로 바꾼다(없는 칸은 null) */
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

        // 한 건도 없으면 0이 아니라 null이다(통계 없음과 인원 0명을 구분한다)
        BigDecimal value() {
            return count == 0 ? null : sum.divide(BigDecimal.valueOf(count), RESULT_SCALE, RoundingMode.HALF_UP);
        }
    }
}
