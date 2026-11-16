package com.whiteeyes.admin.event.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;

import lombok.extern.slf4j.Slf4j;
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
@Slf4j
@Service
public class CongestionEventService {

    // 필드: 혼잡 단계 판정, 이벤트 저장소, 알림 발행기, 최소 표본 초 수, 종료 유지 시간
    private final LevelService levelService;
    private final EventRepository eventRepository;
    private final NotificationPublisher notificationPublisher;
    private final int minSampleSec;
    private final Duration closeHold;

    // 환경변수 값을 직접 받는다. closeHoldMin(분)은 Duration으로 바꿔 둔다
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
        // 표본이 부족한 분은 건너뛴다. below_since 시계도 멈춰 있어야 불완전한 분 때문에 이벤트가 닫히지 않는다
        if (sampleSec < minSampleSec) {
            log.debug("혼잡 이벤트 판정 건너뜀: 결과 초 수 부족 zoneId={} statAt={} sampleSec={}", zoneId, statAt, sampleSec);
            return;
        }
        // 구역 기준으로 혼잡 단계를 판정한다(구역별 기준값이 있으면 그것, 없으면 기본 기준)
        LevelTarget target = LevelTarget.zone(zoneId);
        CongestionLevel level = levelService.judgeLevel(target, density);
        // 이 구역에 이미 열린 혼잡 이벤트가 있는지(구역당 하나가 규칙)
        Event openEvent = eventRepository
                .findFirstByZoneIdAndTypeAndEndedAtIsNullOrderByIdAsc(zoneId, EventType.CONGESTION)
                .orElse(null);

        // 주의 단계 이상이면 열거나 갱신하고, 미만이고 열린 이벤트가 있을 때만 종료 여부를 따진다
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
        // 평균 인원을 반올림한 정수. 이벤트의 최고 인원으로 쓴다
        int peakCount = countAvg.setScale(0, RoundingMode.HALF_UP).intValueExact();
        if (openEvent != null) {
            // 더 높은 단계·밀집도·인원이면 이벤트의 최고 값을 올린다(내려가는 값은 반영되지 않는다)
            openEvent.raisePeak(level.getLevel(), density, peakCount);
            // 다시 주의 이상이 되었으므로 '내려간 시각' 기록을 지워 종료 시계를 되돌린다
            openEvent.clearBelowSince();
            log.debug("혼잡 이벤트 갱신 zoneId={} eventId={} level={}", zoneId, openEvent.getId(), level.getLevel());
            return;
        }
        // 열린 이벤트가 없으면 새로 연다. 당시 기준값(주의·위험 밀집도)을 스냅샷으로 함께 저장한다
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
        // 처음 내려간 분이면 시각만 기록하고 끝낸다(바로 닫지 않고 일정 시간 유지되는지 본다)
        if (openEvent.getBelowSince() == null) {
            openEvent.markBelow(statAt);
            return;
        }
        // 내려간 뒤 경과 시간
        Duration below = Duration.between(openEvent.getBelowSince(), statAt);
        // 유지 시간 이상 내려가 있었다면 일시적인 하락이 아니라고 보고 닫는다. 종료 시각은 마지막 통계 시각
        if (below.compareTo(closeHold) >= 0) {
            openEvent.close(statAt);
            log.info("혼잡 이벤트 종료 zoneId={} eventId={}", zoneId, openEvent.getId());
        }
    }

    // 0.7이 7E-1로 바뀌지 않게 toPlainString으로 쓴다
    // 기준값을 JSON 문자열로 만든다(위 주석: 지수 표기 방지)
    private static String toSnapshotJson(LevelThresholds thresholds) {
        return "{\"warnDensity\":" + thresholds.warnDensity().toPlainString() + ",\"dangerDensity\":"
                + thresholds.dangerDensity().toPlainString() + "}";
    }
}
