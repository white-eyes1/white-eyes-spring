/*
 * [해석본] 원본: admin/src/main/java/com/whiteeyes/admin/stat/scheduler/StatAggregationScheduler.java
 * 관련 이슈: #18(FNC-A-028)
 *
 * [무엇] 1초마다 1분 통계 마감을 부르는 예약 작업.
 * [왜 이 이름·위치] code-conventions.md의 예약 작업 이름 예시가 정확히 'StatAggregationScheduler'이고,
 *   package-structure.md는 'stat/scheduler/'에 두라고 한다. 시간별·일별 집계(FNC-A-029·030)도 나중에 이 클래스에 붙는다.
 * [규칙] package-structure.md: scheduler는 "정해진 시각에 service 호출"만 하고 "로직 직접 구현"은 하지 않는다.
 *   그래서 이 클래스는 현재 시각을 구해 서비스에 넘기는 일만 한다.
 */
package com.whiteeyes.admin.stat.scheduler;

import java.time.LocalDateTime;
import java.time.ZoneId;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.whiteeyes.admin.stat.service.MinuteStatService;

@Component
public class StatAggregationScheduler {

    // [해석] 서버·JVM 시간대도 KST로 맞추지만(overview.md), 그 설정이 빠진 환경에서도 틀리지 않도록 시간대를 직접 지정한다.
    //        #16의 ResultReceiveService가 결과 시각을 KST로 바꾸는 방식과 같다. 두 쪽 기준이 다르면 분이 어긋난다.
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final MinuteStatService minuteStatService;

    public StatAggregationScheduler(MinuteStatService minuteStatService) {
        this.minuteStatService = minuteStatService;
    }

    // 마감 대기 시간(초 단위)을 지키려고 매초 확인한다(FNC-A-028 '예약 작업, 매초 확인').
    // 앞 실행이 끝난 뒤 1초를 쉬어서 저장이 느려져도 실행이 겹치지 않게 한다
    // [해석] fixedRate(시작 기준 1초마다)가 아니라 fixedDelay(끝난 뒤 1초)를 고른 이유: DB가 느려 한 번 실행이 1초를 넘어도
    //        다음 실행이 겹쳐 같은 분을 동시에 처리하지 않는다. 그 대신 확인 간격이 조금 늘 수 있는데,
    //        마감이 1~2초 늦어지는 것은 문제가 되지 않는다.
    @Scheduled(fixedDelay = 1000)
    public void closeEndedMinutes() {
        minuteStatService.closeEndedMinutes(LocalDateTime.now(KST));
    }
}
