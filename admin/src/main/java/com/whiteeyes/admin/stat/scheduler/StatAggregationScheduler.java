package com.whiteeyes.admin.stat.scheduler;

import java.time.LocalDateTime;
import java.time.ZoneId;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.whiteeyes.admin.stat.service.MinuteStatService;

/** 1분 통계 저장(FNC-A-028) */
@Component
public class StatAggregationScheduler {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final MinuteStatService minuteStatService;

    public StatAggregationScheduler(MinuteStatService minuteStatService) {
        this.minuteStatService = minuteStatService;
    }

    // 마감 대기 시간(초 단위)을 지키려고 매초 확인한다(FNC-A-028 '예약 작업, 매초 확인').
    // 앞 실행이 끝난 뒤 1초를 쉬어서 저장이 느려져도 실행이 겹치지 않게 한다
    @Scheduled(fixedDelay = 1000)
    public void closeEndedMinutes() {
        minuteStatService.closeEndedMinutes(LocalDateTime.now(KST));
    }
}
