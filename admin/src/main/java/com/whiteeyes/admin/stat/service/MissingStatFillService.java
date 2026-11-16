package com.whiteeyes.admin.stat.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import com.whiteeyes.common.stat.repository.ZoneStatHourlyRepository;
import com.whiteeyes.common.stat.repository.ZoneStatMinuteRepository;

/** 빠진 집계 보충(FNC-A-031) */
@Slf4j
@Service
@RequiredArgsConstructor
public class MissingStatFillService {

    /** 시간별은 최근 48시간, 일별은 최근 7일까지 찾는다(FNC-A-031 처리 순서). */
    static final int HOURLY_LOOKBACK_HOURS = 48;

    static final int DAILY_LOOKBACK_DAYS = 7;

    // 1분 통계에는 있는데 시간별에 없는 구역을 찾는 데 쓴다
    private final ZoneStatMinuteRepository zoneStatMinuteRepository;
    // 시간별 통계에는 있는데 일별에 없는 구역을 찾는 데 쓴다
    private final ZoneStatHourlyRepository zoneStatHourlyRepository;
    // 빠진 시간별 행을 다시 만든다
    private final HourlyStatService hourlyStatService;
    // 빠진 일별 행을 다시 만든다
    private final DailyStatService dailyStatService;

    /**
     * 시간별을 먼저 채운 뒤 일별을 채운다(일별이 시간별을 읽으므로). 다시 실행해도 안전하다.
     * 한 시간·하루가 실패해도 다음 것을 계속한다.
     *
     * @param now KST 현재 시각. 진행 중인 현재 시간과 오늘은 제외한다
     */
    public void fillMissing(LocalDateTime now) {
        // 일별이 시간별을 재료로 쓰므로 시간별을 먼저 채운다. 현재 시각을 정시로 내려 '진행 중인 시간'을 구분한다
        int hours = fillHourly(now.truncatedTo(ChronoUnit.HOURS));
        // 오늘 날짜를 기준으로 어제부터 거슬러 올라가며 본다
        int days = fillDaily(now.toLocalDate());
        if (hours > 0 || days > 0) {
            log.info("빠진 집계 보충 시간별={}건 일별={}건", hours, days);
        }
    }

    // 현재 시각이 속한 시간은 아직 진행 중이라 제외하고, 그 직전 시간부터 48시간 전까지 본다
    private int fillHourly(LocalDateTime currentHour) {
        int filled = 0;
        // back=1이 직전 시간이다. 현재 시간(진행 중)은 포함하지 않는다
        for (int back = 1; back <= HOURLY_LOOKBACK_HOURS; back++) {
            LocalDateTime hourStart = currentHour.minusHours(back);
            try {
                // 그 1시간에 1분 통계는 있는데 시간별 행이 없는 구역 번호만 가져온다
                List<Long> zoneIds =
                        zoneStatMinuteRepository.findZoneIdsMissingHourly(hourStart, hourStart.plusHours(1));
                // 빠진 구역이 없으면 이 시간은 건너뛴다
                if (zoneIds.isEmpty()) {
                    continue;
                }
                // 빠진 구역만 골라 다시 묶는다(이미 있는 구역의 행은 건드리지 않는다)
                hourlyStatService.aggregateHour(hourStart, zoneIds);
                // 보충한 구역 수를 센다(로그용)
                filled += zoneIds.size();
                // 한 시간이 실패해도 다음 시간 보충은 계속한다. 실패한 시간은 다음 실행 때 다시 찾아진다
            } catch (RuntimeException e) {
                log.error("시간별 통계 보충 실패 statAt={}", hourStart, e);
            }
        }
        return filled;
    }

    // 오늘은 진행 중이라 제외하고, 어제부터 7일 전까지 본다
    private int fillDaily(LocalDate today) {
        int filled = 0;
        // back=1이 어제다. 오늘(진행 중)은 포함하지 않는다
        for (int back = 1; back <= DAILY_LOOKBACK_DAYS; back++) {
            LocalDate date = today.minusDays(back);
            try {
                // 그 날짜에 시간별 통계는 있는데 일별 행이 없는 구역 번호만 가져온다
                List<Long> zoneIds = zoneStatHourlyRepository.findZoneIdsMissingDaily(
                        date, date.atStartOfDay(), date.plusDays(1).atStartOfDay());
                if (zoneIds.isEmpty()) {
                    continue;
                }
                // 빠진 구역만 골라 다시 묶는다
                dailyStatService.aggregateDay(date, zoneIds);
                filled += zoneIds.size();
            } catch (RuntimeException e) {
                log.error("일별 통계 보충 실패 statDate={}", date, e);
            }
        }
        return filled;
    }
}
