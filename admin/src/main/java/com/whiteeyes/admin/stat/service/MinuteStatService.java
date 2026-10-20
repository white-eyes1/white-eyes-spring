package com.whiteeyes.admin.stat.service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.whiteeyes.admin.zone.service.ZoneQueryService;
import com.whiteeyes.common.stat.repository.ZoneStatMinuteRepository;

/** 1분 통계 저장(FNC-A-028) */
@Service
public class MinuteStatService {

    private static final Logger log = LoggerFactory.getLogger(MinuteStatService.class);

    private final MinuteAccumulatorStore minuteAccumulatorStore;
    private final ZoneQueryService zoneQueryService;
    private final ZoneStatMinuteRepository zoneStatMinuteRepository;
    private final Duration closeGrace;

    public MinuteStatService(
            MinuteAccumulatorStore minuteAccumulatorStore,
            ZoneQueryService zoneQueryService,
            ZoneStatMinuteRepository zoneStatMinuteRepository,
            // 테스트 application.yml이 main 설정을 가리므로 yml을 거치지 않고 환경변수를 바로 읽는다(기본 5초, conventions.md)
            @Value("${MINUTE_CLOSE_GRACE_SEC:5}") long closeGraceSec) {
        this.minuteAccumulatorStore = minuteAccumulatorStore;
        this.zoneQueryService = zoneQueryService;
        this.zoneStatMinuteRepository = zoneStatMinuteRepository;
        this.closeGrace = Duration.ofSeconds(closeGraceSec);
    }

    /**
     * 분 끝 + 마감 대기 시간이 지난 누적기를 마감해 구역별 1분 통계로 저장한다(FNC-A-028).
     * 결과가 하나도 없었던 분은 누적기가 없으므로 행을 만들지 않는다.
     *
     * @param now KST 현재 시각
     */
    public void closeEndedMinutes(LocalDateTime now) {
        List<MinuteAccumulator> endedMinutes = minuteAccumulatorStore.closeMinutesEndedBy(now.minus(closeGrace));
        if (endedMinutes.isEmpty()) {
            return;
        }
        Set<Long> zoneIds =
                endedMinutes.stream().map(MinuteAccumulator::getZoneId).collect(Collectors.toSet());
        // 마감 시점의 현재 면적으로 계산한다(면적을 바꾸면 이후 통계부터 적용, tables.md 'zone')
        Map<Long, BigDecimal> areaByZone = zoneQueryService.findAreasByZoneIds(zoneIds);

        for (MinuteAccumulator accumulator : endedMinutes) {
            save(accumulator, areaByZone.get(accumulator.getZoneId()));
        }
    }

    // 실패하면 로그만 남기고 그 누적기는 버린다(재시도 없음, FNC-A-028). 누적기는 이미 마감돼 store에서 빠져 있다
    private void save(MinuteAccumulator accumulator, BigDecimal areaM2) {
        if (areaM2 == null) {
            log.error("1분 통계 저장 실패: 구역 면적 없음 zoneId={} statAt={}",
                    accumulator.getZoneId(), accumulator.getMinute());
            return;
        }
        try {
            zoneStatMinuteRepository.upsert(MinuteStatCalculator.calculate(accumulator, areaM2));
        } catch (RuntimeException e) {
            log.error("1분 통계 저장 실패 zoneId={} statAt={}", accumulator.getZoneId(), accumulator.getMinute(), e);
            return;
        }
        // 1분마다 구역 수만큼 반복되는 처리라 DEBUG로만 남김(code-conventions 7-3)
        log.debug("1분 통계 저장 zoneId={} statAt={} sampleSec={}",
                accumulator.getZoneId(), accumulator.getMinute(), accumulator.getSampleSec());
        // TODO(FNC-A-033·034): 2단계에서 혼잡·급증 이벤트 검사를 여기에 붙인다(FNC-A-028 처리 순서 4)
    }
}
