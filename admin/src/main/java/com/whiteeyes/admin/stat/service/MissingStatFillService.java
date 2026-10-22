package com.whiteeyes.admin.stat.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.whiteeyes.common.stat.repository.ZoneStatHourlyRepository;
import com.whiteeyes.common.stat.repository.ZoneStatMinuteRepository;

/** 빠진 집계 보충(FNC-A-031) */
@Service
public class MissingStatFillService {

    private static final Logger log = LoggerFactory.getLogger(MissingStatFillService.class);

    /** 시간별은 최근 48시간, 일별은 최근 7일까지 찾는다(FNC-A-031 처리 순서). */
    static final int HOURLY_LOOKBACK_HOURS = 48;
    static final int DAILY_LOOKBACK_DAYS = 7;

    private final ZoneStatMinuteRepository zoneStatMinuteRepository;
    private final ZoneStatHourlyRepository zoneStatHourlyRepository;
    private final HourlyStatService hourlyStatService;
    private final DailyStatService dailyStatService;

    public MissingStatFillService(
            ZoneStatMinuteRepository zoneStatMinuteRepository,
            ZoneStatHourlyRepository zoneStatHourlyRepository,
            HourlyStatService hourlyStatService,
            DailyStatService dailyStatService) {
        this.zoneStatMinuteRepository = zoneStatMinuteRepository;
        this.zoneStatHourlyRepository = zoneStatHourlyRepository;
        this.hourlyStatService = hourlyStatService;
        this.dailyStatService = dailyStatService;
    }

    /**
     * 시간별을 먼저 채운 뒤 일별을 채운다(일별이 시간별을 읽으므로). 다시 실행해도 안전하다.
     * 한 시간·하루가 실패해도 다음 것을 계속한다.
     *
     * @param now KST 현재 시각. 진행 중인 현재 시간과 오늘은 제외한다
     */
    public void fillMissing(LocalDateTime now) {
        int hours = fillHourly(now.truncatedTo(ChronoUnit.HOURS));
        int days = fillDaily(now.toLocalDate());
        if (hours > 0 || days > 0) {
            log.info("빠진 집계 보충 시간별={}건 일별={}건", hours, days);
        }
    }

    // 현재 시각이 속한 시간은 아직 진행 중이라 제외하고, 그 직전 시간부터 48시간 전까지 본다
    private int fillHourly(LocalDateTime currentHour) {
        int filled = 0;
        for (int back = 1; back <= HOURLY_LOOKBACK_HOURS; back++) {
            LocalDateTime hourStart = currentHour.minusHours(back);
            try {
                List<Long> zoneIds =
                        zoneStatMinuteRepository.findZoneIdsMissingHourly(hourStart, hourStart.plusHours(1));
                if (zoneIds.isEmpty()) {
                    continue;
                }
                hourlyStatService.aggregateHour(hourStart, zoneIds);
                filled += zoneIds.size();
            } catch (RuntimeException e) {
                log.error("시간별 통계 보충 실패 statAt={}", hourStart, e);
            }
        }
        return filled;
    }

    // 오늘은 진행 중이라 제외하고, 어제부터 7일 전까지 본다
    private int fillDaily(LocalDate today) {
        int filled = 0;
        for (int back = 1; back <= DAILY_LOOKBACK_DAYS; back++) {
            LocalDate date = today.minusDays(back);
            try {
                List<Long> zoneIds = zoneStatHourlyRepository.findZoneIdsMissingDaily(
                        date, date.atStartOfDay(), date.plusDays(1).atStartOfDay());
                if (zoneIds.isEmpty()) {
                    continue;
                }
                dailyStatService.aggregateDay(date, zoneIds);
                filled += zoneIds.size();
            } catch (RuntimeException e) {
                log.error("일별 통계 보충 실패 statDate={}", date, e);
            }
        }
        return filled;
    }
}
