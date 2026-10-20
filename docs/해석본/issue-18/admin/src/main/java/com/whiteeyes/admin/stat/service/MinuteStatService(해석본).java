/*
 * [해석본] 원본: admin/src/main/java/com/whiteeyes/admin/stat/service/MinuteStatService.java
 * 관련 이슈: #18(FNC-A-028)
 *
 * [무엇] 1분 통계 저장의 본체. 매초 스케줄러가 부르면 아래 순서로 일한다(명세 처리 순서와 번호를 맞춤).
 *   1. 분 끝 + 5초(MINUTE_CLOSE_GRACE_SEC)가 지난 바구니를 마감해 꺼낸다(MinuteAccumulatorStore)
 *   2. 구역 면적을 조회하고 값을 계산한다(ZoneQueryService, MinuteStatCalculator)
 *   3. (zone_id, stat_at) 기준으로 넣거나 덮어쓴다(ZoneStatMinuteRepository.upsert)
 *   4. 이벤트 검사 — 2단계라 TODO 자리만 남김(결정 7)
 *   5. 누적기 삭제 — 1번에서 마감할 때 이미 저장소에서 빠졌다
 * [왜 5초를 기다리나] 분석 서버는 1초마다 결과를 보낸다. 14:03:59의 결과는 14:04:00을 조금 넘겨 도착할 수 있으므로,
 *   14:04:00에 바로 마감하면 마지막 몇 초가 빠진다. 5초 여유를 두고 14:04:05에 마감한다(conventions.md).
 * [사용자와 합의한 결정 6] 명세대로 '시각 기준'으로 마감한다. 관리자 앱이 오래 꺼졌다 켜져서 재전송분이 2,000건을 넘으면
 *   여러 묶음으로 나눠 오는데, 첫 묶음이 들어온 다음 초에 그 분이 마감돼 뒤 묶음이 버려질 수 있다.
 *   이 한계는 PR에 '(검토 필요) 알려진 한계'로 적었다.
 * [실패 처리] 명세: "저장 실패 시 로그 남기고 누적기는 버림(재시도 없음)". 한 구역 실패가 다른 구역을 막지 않게
 *   구역마다 따로 처리한다.
 */
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

// [해석] @Transactional을 붙이지 않았다. 여기서 트랜잭션을 열면 모든 구역 저장이 한 트랜잭션에 묶여,
//        한 구역이 실패할 때 앞서 저장한 구역까지 롤백된다. 트랜잭션은 리포지토리의 upsert가 구역마다 따로 연다.
@Service
public class MinuteStatService {

    // [해석] 로깅은 SLF4J API로 쓰고 실제 출력은 Log4j2가 한다(code-conventions 7, decisions/0009).
    private static final Logger log = LoggerFactory.getLogger(MinuteStatService.class);

    private final MinuteAccumulatorStore minuteAccumulatorStore;
    private final ZoneQueryService zoneQueryService;
    private final ZoneStatMinuteRepository zoneStatMinuteRepository;
    // [해석] 초를 숫자(long)로 들고 다니지 않고 Duration으로 바꿔 둔다. now.minus(closeGrace)처럼 단위 실수 없이 쓸 수 있다.
    private final Duration closeGrace;

    public MinuteStatService(
            MinuteAccumulatorStore minuteAccumulatorStore,
            ZoneQueryService zoneQueryService,
            ZoneStatMinuteRepository zoneStatMinuteRepository,
            // 테스트 application.yml이 main 설정을 가리므로 yml을 거치지 않고 환경변수를 바로 읽는다(기본 5초, conventions.md)
            // [해석] 계획에서는 application.yml에 stat.minute-close-grace-sec를 두려 했지만 바꿨다.
            //        테스트 리소스의 application.yml이 main의 같은 이름 파일을 통째로 가려서, yml에 둔 값은 테스트에서 안 보인다.
            //        그렇다고 테스트 yml을 고치면 #15·#16·#17이 같은 내용으로 맞춰 둔 공유 파일이 달라진다.
            //        ${MINUTE_CLOSE_GRACE_SEC:5}는 "환경변수가 있으면 그 값, 없으면 5"라는 뜻이다.
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
    // [해석] 현재 시각을 안에서 LocalDateTime.now()로 구하지 않고 파라미터로 받는다. 테스트에서 "14:04:04면 마감 안 함,
    //        14:04:05면 마감"처럼 원하는 시각을 넣어 확인할 수 있게 하려는 것. 실제 시각은 스케줄러가 넣어 준다.
    public void closeEndedMinutes(LocalDateTime now) {
        // [해석] 처리 순서 1. "지금 ≥ 분 끝 + 5초"는 "분 끝 ≤ 지금 − 5초"와 같다. 그래서 지금에서 5초를 뺀 시각을 기준으로 넘긴다.
        //        꺼내는 순간 저장소에서 빠지고 마감 기록이 남으므로, 이 뒤에 도착하는 같은 분의 결과는 버려진다(#16).
        List<MinuteAccumulator> endedMinutes = minuteAccumulatorStore.closeMinutesEndedBy(now.minus(closeGrace));
        // [해석] 매초 불리는데 대부분의 초에는 마감할 것이 없다. 그때는 DB 조회도 하지 않고 바로 끝낸다.
        if (endedMinutes.isEmpty()) {
            return;
        }
        Set<Long> zoneIds =
                endedMinutes.stream().map(MinuteAccumulator::getZoneId).collect(Collectors.toSet());
        // 마감 시점의 현재 면적으로 계산한다(면적을 바꾸면 이후 통계부터 적용, tables.md 'zone')
        // [해석] 마감된 구역들의 면적을 쿼리 한 번으로 가져온다(구역마다 조회하지 않음).
        Map<Long, BigDecimal> areaByZone = zoneQueryService.findAreasByZoneIds(zoneIds);

        for (MinuteAccumulator accumulator : endedMinutes) {
            save(accumulator, areaByZone.get(accumulator.getZoneId()));
        }
    }

    // 실패하면 로그만 남기고 그 누적기는 버린다(재시도 없음, FNC-A-028). 누적기는 이미 마감돼 store에서 빠져 있다
    private void save(MinuteAccumulator accumulator, BigDecimal areaM2) {
        // [해석] 면적을 못 찾으면 밀집도를 계산할 수 없다. 구역이 지워졌거나 데이터가 어긋난 상황이라 사람이 봐야 하므로
        //        ERROR로 남긴다(code-conventions 7: "1분 통계 저장 실패"가 ERROR의 예시).
        if (areaM2 == null) {
            log.error("1분 통계 저장 실패: 구역 면적 없음 zoneId={} statAt={}",
                    accumulator.getZoneId(), accumulator.getMinute());
            return;
        }
        try {
            // [해석] 처리 순서 2·3. 계산 → 덮어쓰기 저장.
            zoneStatMinuteRepository.upsert(MinuteStatCalculator.calculate(accumulator, areaM2));
        } catch (RuntimeException e) {
            // [해석] DB가 끊기는 등 저장이 실패해도 예외를 밖으로 던지지 않는다. 던지면 for문이 멈춰 뒤 구역들이 저장되지 않는다.
            //        마지막 인자 e를 넘기면 Log4j2가 스택 트레이스까지 남긴다(원인 추적용).
            //        재시도하지 않는 이유: 명세가 그렇게 정했고, 다음 분 처리를 늦추지 않기 위해서다.
            log.error("1분 통계 저장 실패 zoneId={} statAt={}", accumulator.getZoneId(), accumulator.getMinute(), e);
            return;
        }
        // 1분마다 구역 수만큼 반복되는 처리라 DEBUG로만 남김(code-conventions 7-3)
        log.debug("1분 통계 저장 zoneId={} statAt={} sampleSec={}",
                accumulator.getZoneId(), accumulator.getMinute(), accumulator.getSampleSec());
        // TODO(FNC-A-033·034): 2단계에서 혼잡·급증 이벤트 검사를 여기에 붙인다(FNC-A-028 처리 순서 4)
        // [해석] 사용자와 합의한 결정 7. 이벤트 검사는 이슈 #18의 범위 밖(2단계, decisions/0011)이라 코드는 넣지 않고,
        //        붙일 위치만 남긴다. 저장에 성공한 행만 검사해야 하므로 위치는 저장 성공 바로 뒤다.
    }
}
