package com.whiteeyes.admin.event.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.whiteeyes.admin.notification.service.NotificationPublisher;
import com.whiteeyes.common.event.entity.Event;
import com.whiteeyes.common.event.entity.EventType;
import com.whiteeyes.common.event.repository.EventRepository;
import com.whiteeyes.common.level.service.CongestionLevel;
import com.whiteeyes.common.level.service.LevelService;
import com.whiteeyes.common.level.service.LevelTarget;
import com.whiteeyes.common.level.service.LevelThresholds;

/**
 * 혼잡 이벤트 판정(FNC-A-033). 1분 통계를 저장한 직후 구역이 '주의' 이상이면 이벤트를 열거나 갱신하고,
 * 내려가 일정 시간 유지되면 닫는다. 새 이벤트는 관리자 화면 알림(FNC-A-048)으로 알린다.
 */
@Service
public class CongestionEventService {

    private static final Logger log = LoggerFactory.getLogger(CongestionEventService.class);

    private final LevelService levelService;
    private final EventRepository eventRepository;
    private final NotificationPublisher notificationPublisher;
    private final int minSampleSec;
    private final Duration closeHold;

    public CongestionEventService(
            LevelService levelService,
            EventRepository eventRepository,
            NotificationPublisher notificationPublisher,
            // 테스트 application.yml이 main 설정을 가리므로 yml을 거치지 않고 환경변수를 바로 읽는다(기본값 conventions.md)
            @Value("${EVENT_MIN_SAMPLE_SEC:30}") int minSampleSec,
            @Value("${EVENT_CLOSE_HOLD_MIN:5}") long closeHoldMin) {
        this.levelService = levelService;
        this.eventRepository = eventRepository;
        this.notificationPublisher = notificationPublisher;
        this.minSampleSec = minSampleSec;
        this.closeHold = Duration.ofMinutes(closeHoldMin);
    }

    /**
     * 저장한 1분 통계 하나로 그 구역의 혼잡 이벤트를 만들거나 갱신하거나 닫는다.
     * 다른 도메인(stat)의 엔티티를 받지 않고 판정에 쓰는 값만 받는다(package-structure 3-2).
     *
     * @param countAvg 1분 평균 인원
     * @param density 평균 밀집도(명/㎡)
     * @param sampleSec 결과가 실제로 들어온 초 수
     */
    @Transactional
    public void judge(Long zoneId, LocalDateTime statAt, BigDecimal countAvg, BigDecimal density, int sampleSec) {
        // 캠 시작 직후 같은 불완전한 1분은 검사하지 않는다. below_since도 건드리지 않는다(FNC-A-033 처리 순서 1)
        if (sampleSec < minSampleSec) {
            log.debug("혼잡 이벤트 판정 건너뜀: 결과 초 수 부족 zoneId={} statAt={} sampleSec={}",
                    zoneId, statAt, sampleSec);
            return;
        }
        LevelTarget target = LevelTarget.zone(zoneId);
        CongestionLevel level = levelService.judgeLevel(target, density);
        Event openEvent = eventRepository
                .findFirstByZoneIdAndTypeAndEndedAtIsNullOrderByIdAsc(zoneId, EventType.CONGESTION)
                .orElse(null);

        if (level.getLevel() >= CongestionLevel.CAUTION.getLevel()) {
            raiseOrOpen(openEvent, zoneId, statAt, countAvg, density, level, target);
        } else if (openEvent != null) {
            closeIfBelowHeld(openEvent, zoneId, statAt);
        }
    }

    // 주의 이상: 열린 이벤트가 있으면 최고 값을 갱신하고, 없으면 새로 연다(FNC-A-033 처리 순서 3)
    private void raiseOrOpen(
            Event openEvent,
            Long zoneId,
            LocalDateTime statAt,
            BigDecimal countAvg,
            BigDecimal density,
            CongestionLevel level,
            LevelTarget target) {
        int peakCount = countAvg.setScale(0, RoundingMode.HALF_UP).intValueExact();
        if (openEvent != null) {
            openEvent.raisePeak(level.getLevel(), density, peakCount);
            openEvent.clearBelowSince();
            log.debug("혼잡 이벤트 갱신 zoneId={} eventId={} level={}",
                    zoneId, openEvent.getId(), level.getLevel());
            return;
        }
        Event created = eventRepository.save(Event.openCongestion(
                zoneId,
                statAt,
                level.getLevel(),
                density,
                peakCount,
                toSnapshotJson(levelService.findThresholds(target))));
        log.info("혼잡 이벤트 생성 zoneId={} eventId={}", zoneId, created.getId());
        // 새 이벤트는 열려 있는 관리자 화면에 알린다(FNC-A-048)
        notificationPublisher.eventCreated(
                created.getId(), EventType.CONGESTION, zoneId, created.getPeakLevel(), statAt);
        notificationPublisher.uncheckedCountChanged();
    }

    // 주의 미만: 처음 내려간 시각을 남기고, 그 뒤 EVENT_CLOSE_HOLD_MIN 이상 유지되면 닫는다(FNC-A-033 처리 순서 4)
    private void closeIfBelowHeld(Event openEvent, Long zoneId, LocalDateTime statAt) {
        if (openEvent.getBelowSince() == null) {
            openEvent.markBelow(statAt);
            return;
        }
        Duration below = Duration.between(openEvent.getBelowSince(), statAt);
        if (below.compareTo(closeHold) >= 0) {
            openEvent.close(statAt);
            log.info("혼잡 이벤트 종료 zoneId={} eventId={}", zoneId, openEvent.getId());
        }
    }

    // 0.7이 7E-1로 바뀌지 않게 toPlainString으로 쓴다
    private static String toSnapshotJson(LevelThresholds thresholds) {
        return "{\"warnDensity\":" + thresholds.warnDensity().toPlainString()
                + ",\"dangerDensity\":" + thresholds.dangerDensity().toPlainString() + "}";
    }
}
