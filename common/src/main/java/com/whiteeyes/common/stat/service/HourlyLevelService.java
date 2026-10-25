package com.whiteeyes.common.stat.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.whiteeyes.common.stat.dto.DashboardTargetType;
import com.whiteeyes.common.stat.dto.HourlyLevelItem;
import com.whiteeyes.common.stat.dto.StatInterval;
import com.whiteeyes.common.stat.dto.StatSeriesPoint;
import com.whiteeyes.common.stat.dto.StatSeriesRequest;
import com.whiteeyes.common.stat.dto.StatTargetType;

/**
 * 시간대별 혼잡 단계(FNC-C-018). 그날 0~23시의 시간별 통계를 혼잡 단계로 바꾼다(지역은 통계 그래프와 같은 규칙으로 합산).
 * 진행 중인 현재 시간은 아직 시간별 통계가 없으므로 그 시간의 1분 통계를 묶어 계산한다.
 */
@Service
public class HourlyLevelService {

    private static final int HOURS_PER_DAY = 24;

    private final StatSeriesService statSeriesService;

    public HourlyLevelService(StatSeriesService statSeriesService) {
        this.statSeriesService = statSeriesService;
    }

    /**
     * @param date 조회할 날짜(KST)
     * @param now 지금(KST). date가 오늘이면 지금 시간을 1분 통계로 계산한다
     * @return 0~23시 24칸. 데이터 없는 시간은 level·density가 null
     * @throws com.whiteeyes.common.region.exception.RegionNotFoundException 없는 지역이면(404)
     * @throws com.whiteeyes.common.region.exception.ZoneNotFoundException 없는 구역이면(404)
     */
    @Transactional(readOnly = true)
    public List<HourlyLevelItem> findHourlyLevels(
            DashboardTargetType targetType, long targetId, LocalDate date, LocalDateTime now) {
        StatTargetType seriesType = targetType == DashboardTargetType.ZONE ? StatTargetType.ZONE : StatTargetType.REGION;
        LocalDateTime dayStart = date.atStartOfDay();
        List<StatSeriesPoint> hours = statSeriesService
                .findSeries(new StatSeriesRequest(
                        seriesType, targetId, StatInterval.HOUR, dayStart, dayStart.plusDays(1)))
                .items();

        List<HourlyLevelItem> result = new ArrayList<>(HOURS_PER_DAY);
        for (int hour = 0; hour < HOURS_PER_DAY; hour++) {
            StatSeriesPoint point = hours.get(hour);
            if (date.equals(now.toLocalDate()) && hour == now.getHour()) {
                LocalDateTime hourStart = dayStart.plusHours(hour);
                point = statSeriesService.findMinuteRollup(seriesType, targetId, hourStart, hourStart.plusHours(1));
            }
            result.add(new HourlyLevelItem(hour, point.density() == null ? null : point.level(), point.density()));
        }
        return result;
    }
}
