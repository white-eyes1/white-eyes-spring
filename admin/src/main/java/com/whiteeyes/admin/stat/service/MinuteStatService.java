package com.whiteeyes.admin.stat.service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.whiteeyes.admin.event.service.CongestionEventService;
import com.whiteeyes.admin.event.service.StaleEventCloseService;
import com.whiteeyes.admin.event.service.SurgeEventService;
import com.whiteeyes.admin.region.service.ZoneService;
import com.whiteeyes.common.stat.entity.ZoneStatMinute;
import com.whiteeyes.common.stat.entity.ZoneStatMinuteId;
import com.whiteeyes.common.stat.repository.ZoneStatMinuteRepository;

/** 1분 통계 저장(FNC-A-028) */
@Slf4j
@Service
public class MinuteStatService {

    // 마감할 1분 누적기를 꺼내는 메모리 저장소
    private final MinuteAccumulatorStore minuteAccumulatorStore;
    // 밀집도 계산에 쓸 구역 면적 조회
    private final ZoneService zoneService;
    // 1분 통계 저장(upsert)과, 급증 비교용 이전 1분 통계 조회
    private final ZoneStatMinuteRepository zoneStatMinuteRepository;
    // 저장한 1분 통계로 혼잡 이벤트를 판정한다(FNC-A-033)
    private final CongestionEventService congestionEventService;
    // 저장한 1분 통계로 급증 이벤트를 판정한다(FNC-A-034)
    private final SurgeEventService surgeEventService;
    // 결과가 끊긴 구역의 열린 이벤트를 닫는다
    private final StaleEventCloseService staleEventCloseService;
    // 분이 끝난 뒤 늦게 도착하는 결과를 기다려 주는 시간(MINUTE_CLOSE_GRACE_SEC)
    private final Duration closeGrace;
    // 결과 끊김 종료를 마지막으로 검사한 분. 스케줄러는 매초 부르므로 분이 바뀔 때 한 번만 검사한다
    private final AtomicReference<LocalDateTime> lastStaleCheckedMinute = new AtomicReference<>();

    /** @param closeGraceSec 분 마감 대기 시간(초). 환경변수 MINUTE_CLOSE_GRACE_SEC, 기본 5초 */
    public MinuteStatService(
            MinuteAccumulatorStore minuteAccumulatorStore,
            ZoneService zoneService,
            ZoneStatMinuteRepository zoneStatMinuteRepository,
            CongestionEventService congestionEventService,
            SurgeEventService surgeEventService,
            StaleEventCloseService staleEventCloseService,
            // 테스트 application.yml이 main 설정을 가리므로 yml을 거치지 않고 환경변수를 바로 읽는다(기본 5초, conventions.md)
            @Value("${MINUTE_CLOSE_GRACE_SEC:5}") long closeGraceSec) {
        this.minuteAccumulatorStore = minuteAccumulatorStore;
        this.zoneService = zoneService;
        this.zoneStatMinuteRepository = zoneStatMinuteRepository;
        this.congestionEventService = congestionEventService;
        this.surgeEventService = surgeEventService;
        this.staleEventCloseService = staleEventCloseService;
        this.closeGrace = Duration.ofSeconds(closeGraceSec);
    }

    /**
     * 분 끝 + 마감 대기 시간이 지난 누적기를 마감해 구역별 1분 통계로 저장한다(FNC-A-028).
     * 결과가 하나도 없었던 분은 누적기가 없으므로 행을 만들지 않는다.
     *
     * @param now KST 현재 시각
     */
    public void closeEndedMinutes(LocalDateTime now) {
        // 지금에서 대기 시간을 뺀 시각까지 끝난 분만 마감한다. 대기 시간 덕에 분 끝 직후에 도착하는 결과를 놓치지 않는다
        List<MinuteAccumulator> endedMinutes = minuteAccumulatorStore.closeMinutesEndedBy(now.minus(closeGrace));
        if (!endedMinutes.isEmpty()) {
            saveEndedMinutes(endedMinutes);
        }
        // 마감할 행이 없는 구역도 매 분 검사한다(FNC-A-028 처리 순서 4). 방금 저장한 분이 보이도록 저장·판정 뒤에 돌린다
        closeStaleEvents(now);
    }

    private void saveEndedMinutes(List<MinuteAccumulator> endedMinutes) {
        // 이번에 마감한 누적기의 구역 번호들. 면적을 한 번에 조회하려는 목적이다
        Set<Long> zoneIds =
                endedMinutes.stream().map(MinuteAccumulator::getZoneId).collect(Collectors.toSet());
        // 마감 시점의 현재 면적으로 계산한다(면적을 바꾸면 이후 통계부터 적용, tables.md 'zone')
        Map<Long, BigDecimal> areaByZone = zoneService.findAreasByZoneIds(zoneIds);

        for (MinuteAccumulator accumulator : endedMinutes) {
            // 구역을 찾지 못해 면적이 없으면 null이 넘어가고, save가 오류 로그만 남기고 건너뛴다
            save(accumulator, areaByZone.get(accumulator.getZoneId()));
        }
    }

    // 마감이 끝난 마지막 분 = (now − 마감 대기)를 분으로 내린 값 − 1분. 실패해도 1분 통계 저장은 막지 않고 다음 분에 다시 검사한다
    // (FNC-A-033 5번)
    private void closeStaleEvents(LocalDateTime now) {
        LocalDateTime lastClosedMinute =
                now.minus(closeGrace).truncatedTo(ChronoUnit.MINUTES).minusMinutes(1);
        // getAndSet으로 이전 값을 읽으면서 새 값을 기록한다. 이전과 같은 분이면 이미 검사했으므로 건너뛴다(스케줄러가 매초 부르기 때문)
        if (lastClosedMinute.equals(lastStaleCheckedMinute.getAndSet(lastClosedMinute))) {
            return;
        }
        try {
            // 마지막으로 마감된 분까지의 통계를 기준으로, 결과가 끊긴 구역의 이벤트를 닫는다
            staleEventCloseService.closeStale(lastClosedMinute);
        } catch (RuntimeException e) {
            log.error("결과 끊김 이벤트 종료 실패 lastClosedMinute={}", lastClosedMinute, e);
        }
    }

    // 실패하면 로그만 남기고 그 누적기는 버린다(재시도 없음, FNC-A-028). 누적기는 이미 마감돼 store에서 빠져 있다
    private void save(MinuteAccumulator accumulator, BigDecimal areaM2) {
        // 면적이 없으면 밀집도를 계산할 수 없다
        if (areaM2 == null) {
            log.error("1분 통계 저장 실패: 구역 면적 없음 zoneId={} statAt={}", accumulator.getZoneId(), accumulator.getMinute());
            return;
        }
        // 계산과 저장을 한 try로 묶어, 어느 쪽이 실패해도 이 누적기만 버리고 다른 구역 처리를 계속한다
        ZoneStatMinute stat;
        try {
            stat = MinuteStatCalculator.calculate(accumulator, areaM2);
            // upsert라 같은 (구역, 분) 행이 이미 있으면 덮어쓴다(재시작 뒤 재전송 등으로 같은 분이 다시 마감될 수 있다)
            zoneStatMinuteRepository.upsert(stat);
        } catch (RuntimeException e) {
            log.error("1분 통계 저장 실패 zoneId={} statAt={}", accumulator.getZoneId(), accumulator.getMinute(), e);
            return;
        }
        // 1분마다 구역 수만큼 반복되는 처리라 DEBUG로만 남김(code-conventions 7-3)
        log.debug(
                "1분 통계 저장 zoneId={} statAt={} sampleSec={}",
                accumulator.getZoneId(),
                accumulator.getMinute(),
                accumulator.getSampleSec());
        // 통계 저장이 끝난 뒤에만 이벤트를 판정한다. 두 판정은 서로 독립이라 하나가 실패해도 다른 하나는 돈다
        judgeCongestionEvent(stat);
        judgeSurgeEvent(stat);
    }

    // 이벤트 판정이 실패해도 이미 저장한 통계는 유지하고 다음 구역을 계속 처리한다(FNC-A-033은 FNC-A-028 저장 뒤에 도는 후속 처리)
    private void judgeCongestionEvent(ZoneStatMinute stat) {
        try {
            congestionEventService.judge(
                    stat.getZoneId(), stat.getStatAt(), stat.getCountAvg(), stat.getDensity(), stat.getSampleSec());
        } catch (RuntimeException e) {
            log.error("혼잡 이벤트 판정 실패 zoneId={} statAt={}", stat.getZoneId(), stat.getStatAt(), e);
        }
    }

    // 비교 시간 전 1분 통계와 비교한다. 혼잡 판정이 실패해도 급증 판정은 따로 돈다(FNC-A-034)
    private void judgeSurgeEvent(ZoneStatMinute stat) {
        try {
            // 비교 시간(분)만큼 이전 시각의 1분 통계를 찾는다
            LocalDateTime previousAt = stat.getStatAt().minusMinutes(surgeEventService.getWindowMin());
            ZoneStatMinute previous = zoneStatMinuteRepository
                    .findById(new ZoneStatMinuteId(stat.getZoneId(), previousAt))
                    .orElse(null);
            surgeEventService.judge(
                    stat.getZoneId(),
                    stat.getStatAt(),
                    stat.getCountAvg(),
                    stat.getDensity(),
                    stat.getSampleSec(),
                    // 비교 대상 통계가 없으면 이전 값은 null로 넘긴다(판정 service가 비교 불가로 처리)
                    previous == null ? null : previous.getCountAvg(),
                    previous == null ? null : Integer.valueOf(previous.getSampleSec()));
        } catch (RuntimeException e) {
            log.error("급증 이벤트 판정 실패 zoneId={} statAt={}", stat.getZoneId(), stat.getStatAt(), e);
        }
    }
}
