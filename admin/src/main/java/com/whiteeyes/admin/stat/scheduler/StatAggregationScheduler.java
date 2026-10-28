package com.whiteeyes.admin.stat.scheduler;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.whiteeyes.admin.stat.service.DailyStatService;
import com.whiteeyes.admin.stat.service.HourlyStatService;
import com.whiteeyes.admin.stat.service.MinuteStatService;
import com.whiteeyes.admin.stat.service.MissingStatFillService;
import com.whiteeyes.admin.stat.service.NowCountService;

/** 1분(FNC-A-028)·시간별(FNC-A-029)·일별(FNC-A-030) 통계 저장과 빠진 집계 보충(FNC-A-031) */
@Component
public class StatAggregationScheduler {

    private static final Logger log = LoggerFactory.getLogger(StatAggregationScheduler.class);
    private static final String KST_ID = "Asia/Seoul";
    private static final ZoneId KST = ZoneId.of(KST_ID);

    private final MinuteStatService minuteStatService;
    private final HourlyStatService hourlyStatService;
    private final DailyStatService dailyStatService;
    private final MissingStatFillService missingStatFillService;
    private final NowCountService nowCountService;

    public StatAggregationScheduler(
            NowCountService nowCountService,
            MinuteStatService minuteStatService,
            HourlyStatService hourlyStatService,
            DailyStatService dailyStatService,
            MissingStatFillService missingStatFillService) {
        this.nowCountService = nowCountService;
        this.minuteStatService = minuteStatService;
        this.hourlyStatService = hourlyStatService;
        this.dailyStatService = dailyStatService;
        this.missingStatFillService = missingStatFillService;
    }

    // 매시 01분 30초(FNC-A-029). 직전 정시 시작 시각부터 지난 1시간을 묶는다
    @Scheduled(cron = "30 1 * * * *", zone = KST_ID)
    public void aggregateLastHour() {
        hourlyStatService.aggregateHour(LocalDateTime.now(KST).truncatedTo(ChronoUnit.HOURS).minusHours(1));
        // 매시 집계 뒤에 놓친 시간·날짜가 있으면 채운다(FNC-A-031). 같은 스레드에서 이어 돌아 정시 집계와 겹치지 않는다
        fillMissing();
    }

    // 앱이 켜질 때 한 번(FNC-A-031). 꺼져 있던 동안 놓친 집계를 채운다
    @EventListener(ApplicationReadyEvent.class)
    public void fillMissingOnStartup() {
        fillMissing();
    }

    // 보충이 실패해도 앱 시작이나 다음 예약 실행을 막지 않는다
    private void fillMissing() {
        try {
            missingStatFillService.fillMissing(LocalDateTime.now(KST));
        } catch (RuntimeException e) {
            log.error("빠진 집계 보충 실패", e);
        }
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

    // 현재 인원 저장(FNC-A-054): 5초마다(NOW_COUNT_SAVE_INTERVAL_SEC). 1분 통계와 따로 돌며 서로의 결과를 쓰지 않는다
    @Scheduled(fixedDelayString = "${NOW_COUNT_SAVE_INTERVAL_SEC:5}", timeUnit = TimeUnit.SECONDS)
    public void saveNowCounts() {
        nowCountService.saveLatest(LocalDateTime.now(KST));
    }
}
