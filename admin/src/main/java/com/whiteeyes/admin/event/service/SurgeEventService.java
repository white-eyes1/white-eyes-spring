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

import com.whiteeyes.common.event.entity.Event;
import com.whiteeyes.common.event.entity.EventType;
import com.whiteeyes.common.event.repository.EventRepository;

/**
 * 급증 이벤트 판정(FNC-A-034). 1분 통계를 저장한 직후 비교 시간 전보다 인원이 기준 증가율 이상 늘었으면 이벤트를
 * 열거나 갱신하고, 기준 아래로 일정 시간 유지되면 닫는다. 새 이벤트의 관리자 알림(SSE)은 FNC-A-048이 맡는다.
 */
@Service
public class SurgeEventService {

    private static final Logger log = LoggerFactory.getLogger(SurgeEventService.class);

    private static final BigDecimal PERCENT = BigDecimal.valueOf(100);
    private static final int RATE_SCALE = 4;
    // event.peak_value DECIMAL(10,4)의 상한. 10분 전 인원이 아주 작으면 증가율이 넘으므로 잘라 저장한다
    private static final BigDecimal MAX_RATE = new BigDecimal("999999.9999");

    private final EventRepository eventRepository;
    private final int minSampleSec;
    private final Duration closeHold;
    private final int windowMin;
    private final BigDecimal ratePct;
    private final BigDecimal minDensity;

    public SurgeEventService(
            EventRepository eventRepository,
            // 테스트 application.yml이 main 설정을 가리므로 yml을 거치지 않고 환경변수를 바로 읽는다(기본값 conventions.md)
            @Value("${EVENT_MIN_SAMPLE_SEC:30}") int minSampleSec,
            @Value("${EVENT_CLOSE_HOLD_MIN:5}") long closeHoldMin,
            @Value("${SURGE_WINDOW_MIN:10}") int windowMin,
            @Value("${SURGE_RATE_PCT:50}") BigDecimal ratePct,
            @Value("${SURGE_MIN_DENSITY:0.2}") BigDecimal minDensity) {
        this.eventRepository = eventRepository;
        this.minSampleSec = minSampleSec;
        this.closeHold = Duration.ofMinutes(closeHoldMin);
        this.windowMin = windowMin;
        this.ratePct = ratePct;
        this.minDensity = minDensity;
    }

    /** 비교 시간(분). 호출하는 쪽이 이 시간 전의 1분 통계를 찾는 데 쓴다. */
    public int getWindowMin() {
        return windowMin;
    }

    /**
     * 저장한 1분 통계 하나로 그 구역의 급증 이벤트를 만들거나 갱신하거나 닫는다.
     * 다른 도메인(stat)의 엔티티를 받지 않고 판정에 쓰는 값만 받는다(package-structure 3-2).
     *
     * @param countAvg 현재 1분 평균 인원
     * @param density 현재 평균 밀집도(명/㎡). 최소 인원 = 최소 밀집도 × 구역 면적이므로 밀집도로 비교한다
     * @param sampleSec 현재 결과가 실제로 들어온 초 수
     * @param previousCountAvg 비교 시간 전 1분 평균 인원. 그 시각의 행이 없으면 null
     * @param previousSampleSec 비교 시간 전 결과 초 수. 그 시각의 행이 없으면 null
     */
    @Transactional
    public void judge(
            Long zoneId,
            LocalDateTime statAt,
            BigDecimal countAvg,
            BigDecimal density,
            int sampleSec,
            BigDecimal previousCountAvg,
            Integer previousSampleSec) {
        // 현재 결과가 적은 분은 판단 근거가 없어 건너뛴다. 열린 이벤트의 below_since도 건드리지 않는다(FNC-A-033과 같음)
        if (sampleSec < minSampleSec) {
            return;
        }
        Event openEvent = eventRepository
                .findFirstByZoneIdAndTypeAndEndedAtIsNullOrderByIdAsc(zoneId, EventType.SURGE)
                .orElse(null);

        boolean conditionMet = isConditionMet(density, sampleSec, previousCountAvg, previousSampleSec);
        if (conditionMet && isRateReached(countAvg, previousCountAvg)) {
            raiseOrOpen(openEvent, zoneId, statAt, countAvg, previousCountAvg);
        } else if (openEvent != null) {
            closeIfBelowHeld(openEvent, zoneId, statAt);
        }
    }

    // 밀집도 미달, 10분 전 행 없음·초 수 부족·인원 0은 '조건 미충족'이다(FNC-A-034 처리 순서 1). 열린 이벤트가 있으면 기준 미만과 같게 처리한다
    private boolean isConditionMet(
            BigDecimal density, int sampleSec, BigDecimal previousCountAvg, Integer previousSampleSec) {
        if (density.compareTo(minDensity) < 0) {
            return false;
        }
        return previousCountAvg != null
                && previousSampleSec != null
                && previousSampleSec >= minSampleSec
                && previousCountAvg.signum() != 0;
    }

    // 증가율 >= 기준 증가율. 나눗셈 오차를 피하려고 (현재 − 이전) × 100 >= 기준 × 이전 으로 비교한다
    private boolean isRateReached(BigDecimal countAvg, BigDecimal previousCountAvg) {
        return countAvg.subtract(previousCountAvg).multiply(PERCENT).compareTo(ratePct.multiply(previousCountAvg)) >= 0;
    }

    // 증가율 >= 기준: 열린 이벤트가 있으면 최고 값을 갱신하고, 없으면 새로 연다(FNC-A-034 처리 순서 3)
    private void raiseOrOpen(
            Event openEvent, Long zoneId, LocalDateTime statAt, BigDecimal countAvg, BigDecimal previousCountAvg) {
        BigDecimal rate = countAvg
                .subtract(previousCountAvg)
                .multiply(PERCENT)
                .divide(previousCountAvg, RATE_SCALE, RoundingMode.HALF_UP)
                .min(MAX_RATE);
        int peakCount = countAvg.setScale(0, RoundingMode.HALF_UP).intValueExact();
        if (openEvent != null) {
            openEvent.raiseSurgePeak(rate, peakCount);
            openEvent.clearBelowSince();
            log.debug("급증 이벤트 갱신 zoneId={} eventId={} rate={}", zoneId, openEvent.getId(), rate);
            return;
        }
        Event created = eventRepository.save(Event.openSurge(zoneId, statAt, rate, peakCount, toSnapshotJson()));
        log.info("급증 이벤트 생성 zoneId={} eventId={}", zoneId, created.getId());
    }

    // 기준 미만 또는 조건 미충족: 처음 내려간 시각을 남기고, 그 뒤 EVENT_CLOSE_HOLD_MIN 이상 유지되면 닫는다(FNC-A-034 처리 순서 4)
    private void closeIfBelowHeld(Event openEvent, Long zoneId, LocalDateTime statAt) {
        if (openEvent.getBelowSince() == null) {
            openEvent.markBelow(statAt);
            return;
        }
        Duration below = Duration.between(openEvent.getBelowSince(), statAt);
        if (below.compareTo(closeHold) >= 0) {
            openEvent.close(statAt);
            log.info("급증 이벤트 종료 zoneId={} eventId={}", zoneId, openEvent.getId());
        }
    }

    // 0.2가 2E-1로 바뀌지 않게 toPlainString으로 쓴다
    private String toSnapshotJson() {
        return "{\"window\":" + windowMin
                + ",\"rate\":" + ratePct.toPlainString()
                + ",\"minDensity\":" + minDensity.toPlainString() + "}";
    }
}
