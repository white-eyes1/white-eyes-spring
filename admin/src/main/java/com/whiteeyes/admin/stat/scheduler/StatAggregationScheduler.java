package com.whiteeyes.admin.stat.scheduler;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.whiteeyes.admin.stat.service.DailyStatService;
import com.whiteeyes.admin.stat.service.HourlyStatService;
import com.whiteeyes.admin.stat.service.MinuteStatService;

/** 1분(FNC-A-028)·시간별(FNC-A-029)·일별(FNC-A-030) 통계 저장 */
@Component
public class StatAggregationScheduler {

    private static final String KST_ID = "Asia/Seoul";
    private static final ZoneId KST = ZoneId.of(KST_ID);

    private final MinuteStatService minuteStatService;
    private final HourlyStatService hourlyStatService;
    private final DailyStatService dailyStatService;

    public StatAggregationScheduler(
            MinuteStatService minuteStatService,
            HourlyStatService hourlyStatService,
            DailyStatService dailyStatService) {
        this.minuteStatService = minuteStatService;
        this.hourlyStatService = hourlyStatService;
        this.dailyStatService = dailyStatService;
    }

    // 매시 01분 30초(FNC-A-029). 직전 정시 시작 시각부터 지난 1시간을 묶는다
    @Scheduled(cron = "30 1 * * * *", zone = KST_ID)
    public void aggregateLastHour() {
        hourlyStatService.aggregateHour(LocalDateTime.now(KST).truncatedTo(ChronoUnit.HOURS).minusHours(1));
    }

    // 매일 00:05(FNC-A-030). 어제 하루를 묶는다
    @Scheduled(cron = "0 5 0 * * *", zone = KST_ID)
    public void aggregateYesterday() {
        dailyStatService.aggregateDay(LocalDate.now(KST).minusDays(1));
    }

    // 마감 대기 시간(초 단위)을 지키려고 매초 확인한다(FNC-A-028 '예약 작업, 매초 확인').
    // 앞 실행이 끝난 뒤 1초를 쉬어서 저장이 느려져도 실행이 겹치지 않게 한다
    @Scheduled(fixedDelay = 1000)
    public void closeEndedMinutes() {
        minuteStatService.closeEndedMinutes(LocalDateTime.now(KST));
    }
}
