/*
 * [해석본] 원본: admin/src/test/java/com/whiteeyes/admin/stat/service/MinuteStatServiceTest.java
 * 관련 이슈: #18(FNC-A-028)
 *
 * [무엇] 1분 통계 저장 흐름(마감 → 면적 조회 → 계산 → 저장)의 단위 테스트.
 * [무엇은 진짜, 무엇은 가짜] 누적기 저장소(MinuteAccumulatorStore)는 진짜 객체를 쓰고, DB가 필요한 두 개
 *   (ZoneQueryService, ZoneStatMinuteRepository)만 Mockito 가짜(mock)로 바꿨다.
 *   저장소까지 가짜로 만들면 '분 끝 + 5초에 마감되는지'를 실제 마감 로직으로 확인할 수 없기 때문이다.
 * [확인하는 것] 명세 FNC-A-028의 처리 순서 1(마감 시각), 예외 처리(저장 실패 시 로그 남기고 버림, 재시도 없음),
 *   그리고 면적이 없을 때와 마감할 것이 없을 때.
 */
package com.whiteeyes.admin.stat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;

import com.whiteeyes.admin.zone.service.ZoneQueryService;
import com.whiteeyes.common.stat.entity.ZoneStatMinute;
import com.whiteeyes.common.stat.repository.ZoneStatMinuteRepository;

@ExtendWith(MockitoExtension.class)
class MinuteStatServiceTest {

    private static final long ZONE_ID = 3L;
    private static final long OTHER_ZONE_ID = 4L;
    private static final LocalDateTime MINUTE = LocalDateTime.of(2026, 10, 12, 14, 3);
    // [해석] 운영 기본값(conventions.md MINUTE_CLOSE_GRACE_SEC=5)과 같은 값으로 테스트한다.
    private static final long CLOSE_GRACE_SEC = 5;

    @Mock
    private ZoneQueryService zoneQueryService;

    @Mock
    private ZoneStatMinuteRepository zoneStatMinuteRepository;

    private MinuteAccumulatorStore minuteAccumulatorStore;
    private MinuteStatService minuteStatService;

    // [해석] @InjectMocks를 쓰지 않고 직접 new로 만든 이유: 생성자에 mock이 아닌 진짜 저장소와 숫자(마감 대기 초)도
    //        넣어야 하기 때문. #16의 ResultReceiveServiceTest와 같은 방식이다.
    @BeforeEach
    void setUp() {
        minuteAccumulatorStore = new MinuteAccumulatorStore();
        minuteStatService = new MinuteStatService(
                minuteAccumulatorStore, zoneQueryService, zoneStatMinuteRepository, CLOSE_GRACE_SEC);
    }

    // [해석] 마감 시각의 경계. conventions.md의 예시 "14:03 구간은 14:04:05에 마감"을 그대로 옮겼다.
    //        1초 전(14:04:04)에는 저장이 한 번도 없어야 하고, 14:04:05에는 정확히 한 번 저장돼야 한다.
    //        ArgumentCaptor: 가짜 리포지토리에 실제로 넘어간 값을 붙잡아, 어떤 행을 저장하려 했는지 확인한다.
    //        밀집도 0.4000 = 40명 ÷ 100㎡. 면적이 계산까지 제대로 전달됐는지 본다.
    @Test
    @DisplayName("FNC-A-028 14:03 구간은 14:04:04에는 마감하지 않고 14:04:05에 마감해 저장")
    void closesMinuteAfterGrace() {
        minuteAccumulatorStore.add(ZONE_ID, MINUTE.withSecond(10), secondResult(40));
        given(zoneQueryService.findAreasByZoneIds(anySet())).willReturn(Map.of(ZONE_ID, new BigDecimal("100.00")));

        minuteStatService.closeEndedMinutes(LocalDateTime.of(2026, 10, 12, 14, 4, 4));
        verify(zoneStatMinuteRepository, never()).upsert(any());

        minuteStatService.closeEndedMinutes(LocalDateTime.of(2026, 10, 12, 14, 4, 5));

        ArgumentCaptor<ZoneStatMinute> saved = ArgumentCaptor.forClass(ZoneStatMinute.class);
        verify(zoneStatMinuteRepository).upsert(saved.capture());
        assertThat(saved.getValue().getZoneId()).isEqualTo(ZONE_ID);
        assertThat(saved.getValue().getStatAt()).isEqualTo(MINUTE);
        assertThat(saved.getValue().getDensity()).isEqualByComparingTo("0.4000");
    }

    // [해석] 명세 예외 처리 "저장 실패 시 로그 남기고 누적기는 버림(재시도 없음)".
    //        - 구역 3 저장이 DB 오류로 실패해도 구역 4는 저장돼야 한다(예외가 for문을 멈추지 않는지).
    //        - 실패한 구역 3의 14:03은 '마감된 분'으로 남아야 한다. 그 분에 결과가 다시 오면 버려진다(add가 false).
    //          그래야 다음 실행에서 일부 데이터로 다시 저장하는 일이 없다.
    @Test
    @DisplayName("FNC-A-028 한 구역 저장이 실패해도 다른 구역은 저장하고, 실패한 누적기는 버림")
    void continuesWhenOneSaveFails() {
        minuteAccumulatorStore.add(ZONE_ID, MINUTE.withSecond(10), secondResult(40));
        minuteAccumulatorStore.add(OTHER_ZONE_ID, MINUTE.withSecond(10), secondResult(30));
        given(zoneQueryService.findAreasByZoneIds(anySet()))
                .willReturn(Map.of(ZONE_ID, new BigDecimal("100.00"), OTHER_ZONE_ID, new BigDecimal("100.00")));
        // 구역 순서(3 → 4)로 저장하므로 첫 저장(구역 3)만 실패시킨다
        // [해석] "구역 3일 때만 예외"처럼 인자 조건으로 걸면, Mockito의 엄격 모드가 구역 4 호출을 '설정과 다른 인자로
        //        불렀다'고 보고 테스트를 실패시킨다. 그래서 '첫 호출은 예외, 두 번째는 정상'으로 순서를 걸었다.
        //        저장 순서가 정렬돼 있어서(closeMinutesEndedBy) 첫 호출이 항상 구역 3이다.
        willThrow(new DataAccessResourceFailureException("DB 연결 끊김"))
                .willDoNothing()
                .given(zoneStatMinuteRepository)
                .upsert(any());

        minuteStatService.closeEndedMinutes(MINUTE.plusMinutes(1).plusSeconds(CLOSE_GRACE_SEC));

        verify(zoneStatMinuteRepository, times(2)).upsert(any());
        verify(zoneStatMinuteRepository).upsert(argThat(stat -> stat.getZoneId() == OTHER_ZONE_ID));
        // 재시도 없음: 실패한 분은 마감된 채로 남아 다음 실행에서 다시 저장하지 않는다
        assertThat(minuteAccumulatorStore.add(ZONE_ID, MINUTE.withSecond(20), secondResult(41))).isFalse();
    }

    // [해석] 면적 조회 결과에 구역 3이 없는 상황(구역이 지워졌거나 데이터가 어긋남). 구역 3은 건너뛰고(ERROR 로그),
    //        구역 4만 저장돼야 한다. upsert가 정확히 한 번(구역 4)만 불렸는지로 확인한다.
    @Test
    @DisplayName("FNC-A-028 면적을 찾지 못한 구역은 저장하지 않음")
    void skipsZoneWithoutArea() {
        minuteAccumulatorStore.add(ZONE_ID, MINUTE.withSecond(10), secondResult(40));
        minuteAccumulatorStore.add(OTHER_ZONE_ID, MINUTE.withSecond(10), secondResult(30));
        given(zoneQueryService.findAreasByZoneIds(anySet()))
                .willReturn(Map.of(OTHER_ZONE_ID, new BigDecimal("100.00")));

        minuteStatService.closeEndedMinutes(MINUTE.plusMinutes(1).plusSeconds(CLOSE_GRACE_SEC));

        verify(zoneStatMinuteRepository).upsert(argThat(stat -> stat.getZoneId() == OTHER_ZONE_ID));
        verify(zoneStatMinuteRepository, times(1)).upsert(any());
    }

    // [해석] 스케줄러는 매초 부르지만 대부분의 초에는 마감할 것이 없다. 그때 DB 조회를 하지 않는지 확인한다
    //        (1초마다 쓸데없는 쿼리가 나가지 않도록). 결과가 하나도 없던 분은 행을 만들지 않는다는 명세도 여기에 해당한다.
    @Test
    @DisplayName("FNC-A-028 마감할 누적기가 없으면 구역 조회도 하지 않음")
    void doesNothingWhenNoEndedMinutes() {
        minuteStatService.closeEndedMinutes(MINUTE);

        verify(zoneQueryService, never()).findAreasByZoneIds(anySet());
        verify(zoneStatMinuteRepository, never()).upsert(any());
    }

    private SecondResult secondResult(int personCount) {
        return new SecondResult(personCount, DirectionCounts.ZERO, DirectionCounts.ZERO, List.of());
    }
}
