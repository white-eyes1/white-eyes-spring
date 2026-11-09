package com.whiteeyes.admin.event.service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.whiteeyes.common.event.entity.Event;
import com.whiteeyes.common.event.entity.EventType;
import com.whiteeyes.common.event.repository.EventRepository;
import com.whiteeyes.common.stat.service.ZoneStatQueryService;

/**
 * 결과 끊김 종료(FNC-A-033 5번, FNC-A-034 5번). 캠 정지·삭제·결과 끊김·구역 삭제로 1분 통계 행이 만들어지지 않으면
 * 판정({@link CongestionEventService#judge}, {@link SurgeEventService#judge})이 돌지 않아 열린 이벤트가 닫히지 않는다.
 * 그래서 열린 혼잡·급증 이벤트를 구역 기준으로 따로 훑어, 판정 가능한 분이 EVENT_CLOSE_HOLD_MIN 이상 끊긴 이벤트를 닫는다.
 * 삭제된 구역의 이벤트도 대상이다.
 */
@Slf4j
@Service
public class StaleEventCloseService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final Duration ONE_MINUTE = Duration.ofMinutes(1);

    private final EventRepository eventRepository;
    private final ZoneStatQueryService zoneStatQueryService;
    private final int minSampleSec;
    private final Duration closeHold;

    // 관리자 앱 시작 분. 재시작 직후 이벤트가 닫혔다 다시 열려 둘로 나뉘지 않게 끊김을 이 시각부터 센다(FNC-A-033 업무 규칙)
    private final AtomicReference<LocalDateTime> startedAt =
            new AtomicReference<>(LocalDateTime.now(KST).truncatedTo(ChronoUnit.MINUTES));

    public StaleEventCloseService(
            EventRepository eventRepository,
            ZoneStatQueryService zoneStatQueryService,
            // 테스트 application.yml이 main 설정을 가리므로 yml을 거치지 않고 환경변수를 바로 읽는다(기본값 conventions.md)
            @Value("${EVENT_MIN_SAMPLE_SEC:30}") int minSampleSec,
            @Value("${EVENT_CLOSE_HOLD_MIN:5}") long closeHoldMin) {
        this.eventRepository = eventRepository;
        this.zoneStatQueryService = zoneStatQueryService;
        this.minSampleSec = minSampleSec;
        this.closeHold = Duration.ofMinutes(closeHoldMin);
    }

    // 생성 시각이 아니라 앱이 준비된 시점으로 다시 기록한다(CamFaultEventService의 재시작 처리와 같은 취지)
    @EventListener(ApplicationReadyEvent.class)
    public void markStarted() {
        markStarted(LocalDateTime.now(KST));
    }

    /** 앱 시작 시각을 정한다. 1분 통계의 분 시작 시각과 비교하도록 분 단위로 내린다. */
    public void markStarted(LocalDateTime at) {
        startedAt.set(at.truncatedTo(ChronoUnit.MINUTES));
    }

    /**
     * 열린 혼잡·급증 이벤트 중 결과가 끊긴 것을 닫는다.
     * 정상 종료가 분 시작 시각끼리(stat_at − below_since ≥ 보류 시간) 비교하는 것과 맞춰, 1분 통계 마감이 끝난
     * 마지막 분(lastClosedMinute)과 기준 시각(마지막 판정 가능 분과 앱 시작 분 중 늦은 것)의 차로 끊긴 시간을 잰다.
     * 정상 종료가 먼저 닫은 이벤트는 열린 이벤트 조회에서 빠지므로 다시 닫히지 않고,
     * 결과가 계속 들어오는 구역은 기준 시각이 lastClosedMinute에 붙어 있어 닫히지 않는다.
     *
     * @param lastClosedMinute 1분 통계 마감이 끝난 가장 마지막 분의 시작 시각
     */
    @Transactional
    public void closeStale(LocalDateTime lastClosedMinute) {
        List<Event> openEvents =
                eventRepository.findByTypeInAndEndedAtIsNull(List.of(EventType.CONGESTION, EventType.SURGE));
        if (openEvents.isEmpty()) {
            return;
        }
        Set<Long> zoneIds = openEvents.stream().map(Event::getZoneId).collect(Collectors.toSet());
        Map<Long, LocalDateTime> lastJudgeableByZone =
                zoneStatQueryService.findLastJudgeableMinuteByZone(zoneIds, minSampleSec);

        LocalDateTime appStartedAt = startedAt.get();
        for (Event event : openEvents) {
            LocalDateTime lastJudgeable = lastJudgeableByZone.get(event.getZoneId());
            LocalDateTime base =
                    lastJudgeable == null || lastJudgeable.isBefore(appStartedAt) ? appStartedAt : lastJudgeable;
            if (Duration.between(base, lastClosedMinute).compareTo(closeHold) < 0) {
                continue;
            }
            // 마지막 판정 가능 분이 없거나 이벤트가 열리기 전이면 연 분의 끝으로 닫는다(ended_at이 started_at보다 앞서지 않게)
            LocalDateTime endedAt = lastJudgeable == null || lastJudgeable.isBefore(event.getStartedAt())
                    ? event.getStartedAt().plus(ONE_MINUTE)
                    : lastJudgeable.plus(ONE_MINUTE);
            event.close(endedAt);
            log.info(
                    "이벤트 종료(결과 끊김) type={} zoneId={} eventId={} endedAt={}",
                    event.getType(),
                    event.getZoneId(),
                    event.getId(),
                    endedAt);
        }
    }
}
