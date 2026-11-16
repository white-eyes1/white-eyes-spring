package com.whiteeyes.admin.stat.scheduler;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.whiteeyes.admin.stat.service.DailyStatService;
import com.whiteeyes.admin.stat.service.HourlyStatService;
import com.whiteeyes.admin.stat.service.MinuteStatService;
import com.whiteeyes.admin.stat.service.MissingStatFillService;

/** 1분(FNC-A-028)·시간별(FNC-A-029)·일별(FNC-A-030) 통계 저장과 빠진 집계 보충(FNC-A-031) */
@Slf4j
@Component
@RequiredArgsConstructor
public class StatAggregationScheduler {

    // @Scheduled의 zone 속성은 문자열 상수만 받으므로 ID를 따로 두고, 시각 계산용 ZoneId도 같은 값으로 만든다
    private static final String KST_ID = "Asia/Seoul";
    private static final ZoneId KST = ZoneId.of(KST_ID);

    // 1분 통계: 마감된 분을 저장(FNC-A-028)
    private final MinuteStatService minuteStatService;
    // 시간별 통계: 1분 통계를 묶음(FNC-A-029)
    private final HourlyStatService hourlyStatService;
    // 일별 통계: 시간별 통계를 묶음(FNC-A-030)
    private final DailyStatService dailyStatService;
    // 놓친 시간별·일별 집계를 채움(FNC-A-031)
    private final MissingStatFillService missingStatFillService;

    /**
     * 지난 1시간의 시간별 통계를 저장한다. cron은 초·분·시·일·월·요일 6필드이며 매시 1분 30초에 돈다.
     */
    // 매시 01분 30초(FNC-A-029). 직전 정시 시작 시각부터 지난 1시간을 묶는다
    @Scheduled(cron = "30 1 * * * *", zone = KST_ID)
    public void aggregateLastHour() {
        // 지금 시각을 정시로 내린 뒤 1시간을 빼면 방금 끝난 시간의 시작 시각이 된다(예: 14:01 → 13:00)
        hourlyStatService.aggregateHour(
                LocalDateTime.now(KST).truncatedTo(ChronoUnit.HOURS).minusHours(1));
        // 매시 집계 뒤에 놓친 시간·날짜가 있으면 채운다(FNC-A-031). 같은 스레드에서 이어 돌아 정시 집계와 겹치지 않는다
        fillMissing();
    }

    // 앱이 켜질 때 한 번(FNC-A-031). 꺼져 있던 동안 놓친 집계를 채운다
    /** 앱이 요청을 받을 준비가 되면 한 번 돌며, 앱이 꺼져 있는 동안 놓친 집계를 채운다. */
    @EventListener(ApplicationReadyEvent.class)
    public void fillMissingOnStartup() {
        fillMissing();
    }

    // 보충이 실패해도 앱 시작이나 다음 예약 실행을 막지 않는다
    /** 빠진 집계 보충을 부르되, 예외는 로그만 남기고 삼킨다(스케줄러 스레드가 죽지 않게). */
    private void fillMissing() {
        try {
            // 현재 시각을 넘겨, 진행 중인 시간·오늘을 제외한 과거 구간만 보충하게 한다
            missingStatFillService.fillMissing(LocalDateTime.now(KST));
        } catch (RuntimeException e) {
            log.error("빠진 집계 보충 실패", e);
        }
    }

    /** 어제 하루의 일별 통계를 저장한다. 매일 00:05에 돌며, 00:01:30의 시간별 집계가 먼저 끝나므로 어제의 마지막 시간별 통계까지 포함된다. */
    // 매일 00:05(FNC-A-030). 어제 하루를 묶는다
    @Scheduled(cron = "0 5 0 * * *", zone = KST_ID)
    public void aggregateYesterday() {
        dailyStatService.aggregateDay(LocalDate.now(KST).minusDays(1));
    }

    /** 분이 끝나고 마감 대기 시간이 지난 1분 누적기를 찾아 저장한다. fixedDelay는 앞 실행이 끝난 뒤부터 센다. */
    // 마감 대기 시간(초 단위)을 지키려고 매초 확인한다(FNC-A-028 '예약 작업, 매초 확인').
    // 앞 실행이 끝난 뒤 1초를 쉬어서 저장이 느려져도 실행이 겹치지 않게 한다
    @Scheduled(fixedDelay = 1000)
    public void closeEndedMinutes() {
        minuteStatService.closeEndedMinutes(LocalDateTime.now(KST));
    }
}
