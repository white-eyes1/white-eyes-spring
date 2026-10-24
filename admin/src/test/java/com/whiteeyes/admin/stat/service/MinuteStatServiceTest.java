package com.whiteeyes.admin.stat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;

import com.whiteeyes.admin.event.service.CongestionEventService;
import com.whiteeyes.admin.event.service.SurgeEventService;
import com.whiteeyes.admin.region.service.ZoneService;
import com.whiteeyes.common.stat.entity.ZoneStatMinute;
import com.whiteeyes.common.stat.entity.ZoneStatMinuteId;
import com.whiteeyes.common.stat.repository.ZoneStatMinuteRepository;

/** 1분 통계 저장(FNC-A-028) */
@ExtendWith(MockitoExtension.class)
class MinuteStatServiceTest {

    private static final long ZONE_ID = 3L;
    private static final long OTHER_ZONE_ID = 4L;
    private static final LocalDateTime MINUTE = LocalDateTime.of(2026, 10, 12, 14, 3);
    private static final long CLOSE_GRACE_SEC = 5;
    private static final int SURGE_WINDOW_MIN = 10;

    @Mock
    private ZoneService zoneService;

    @Mock
    private ZoneStatMinuteRepository zoneStatMinuteRepository;

    @Mock
    private CongestionEventService congestionEventService;

    @Mock
    private SurgeEventService surgeEventService;

    private MinuteAccumulatorStore minuteAccumulatorStore;
    private MinuteStatService minuteStatService;

    @BeforeEach
    void setUp() {
        // 저장할 통계가 없는 테스트는 비교 시간을 읽지 않으므로 엄격한 스텁 검사에서 뺀다
        lenient().when(surgeEventService.getWindowMin()).thenReturn(SURGE_WINDOW_MIN);
        minuteAccumulatorStore = new MinuteAccumulatorStore();
        minuteStatService = new MinuteStatService(
                minuteAccumulatorStore, zoneService, zoneStatMinuteRepository, congestionEventService, surgeEventService, CLOSE_GRACE_SEC);
    }

    @Test
    @DisplayName("FNC-A-028 14:03 구간은 14:04:04에는 마감하지 않고 14:04:05에 마감해 저장")
    void closesMinuteAfterGrace() {
        minuteAccumulatorStore.add(ZONE_ID, MINUTE.withSecond(10), secondResult(40));
        given(zoneService.findAreasByZoneIds(anySet())).willReturn(Map.of(ZONE_ID, new BigDecimal("100.00")));

        minuteStatService.closeEndedMinutes(LocalDateTime.of(2026, 10, 12, 14, 4, 4));
        verify(zoneStatMinuteRepository, never()).upsert(any());

        minuteStatService.closeEndedMinutes(LocalDateTime.of(2026, 10, 12, 14, 4, 5));

        ArgumentCaptor<ZoneStatMinute> saved = ArgumentCaptor.forClass(ZoneStatMinute.class);
        verify(zoneStatMinuteRepository).upsert(saved.capture());
        assertThat(saved.getValue().getZoneId()).isEqualTo(ZONE_ID);
        assertThat(saved.getValue().getStatAt()).isEqualTo(MINUTE);
        assertThat(saved.getValue().getDensity()).isEqualByComparingTo("0.4000");
    }

    @Test
    @DisplayName("FNC-A-028 한 구역 저장이 실패해도 다른 구역은 저장하고, 실패한 누적기는 버림")
    void continuesWhenOneSaveFails() {
        minuteAccumulatorStore.add(ZONE_ID, MINUTE.withSecond(10), secondResult(40));
        minuteAccumulatorStore.add(OTHER_ZONE_ID, MINUTE.withSecond(10), secondResult(30));
        given(zoneService.findAreasByZoneIds(anySet()))
                .willReturn(Map.of(ZONE_ID, new BigDecimal("100.00"), OTHER_ZONE_ID, new BigDecimal("100.00")));
        // 구역 순서(3 → 4)로 저장하므로 첫 저장(구역 3)만 실패시킨다
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

    @Test
    @DisplayName("FNC-A-028 면적을 찾지 못한 구역은 저장하지 않음")
    void skipsZoneWithoutArea() {
        minuteAccumulatorStore.add(ZONE_ID, MINUTE.withSecond(10), secondResult(40));
        minuteAccumulatorStore.add(OTHER_ZONE_ID, MINUTE.withSecond(10), secondResult(30));
        given(zoneService.findAreasByZoneIds(anySet()))
                .willReturn(Map.of(OTHER_ZONE_ID, new BigDecimal("100.00")));

        minuteStatService.closeEndedMinutes(MINUTE.plusMinutes(1).plusSeconds(CLOSE_GRACE_SEC));

        verify(zoneStatMinuteRepository).upsert(argThat(stat -> stat.getZoneId() == OTHER_ZONE_ID));
        verify(zoneStatMinuteRepository, times(1)).upsert(any());
    }

    @Test
    @DisplayName("FNC-A-028 마감할 누적기가 없으면 구역 조회도 하지 않음")
    void doesNothingWhenNoEndedMinutes() {
        minuteStatService.closeEndedMinutes(MINUTE);

        verify(zoneService, never()).findAreasByZoneIds(anySet());
        verify(zoneStatMinuteRepository, never()).upsert(any());
    }

    @Test
    @DisplayName("FNC-A-033 1분 통계를 저장한 직후 저장한 값으로 혼잡 이벤트를 판정")
    void judgesCongestionEventAfterSave() {
        minuteAccumulatorStore.add(ZONE_ID, MINUTE.withSecond(10), secondResult(40));
        given(zoneService.findAreasByZoneIds(anySet())).willReturn(Map.of(ZONE_ID, new BigDecimal("100.00")));

        minuteStatService.closeEndedMinutes(MINUTE.plusMinutes(1).plusSeconds(CLOSE_GRACE_SEC));

        InOrder inOrder = inOrder(zoneStatMinuteRepository, congestionEventService);
        inOrder.verify(zoneStatMinuteRepository).upsert(any());
        inOrder.verify(congestionEventService).judge(
                eq(ZONE_ID), eq(MINUTE), any(), argThat(density -> density.compareTo(new BigDecimal("0.4000")) == 0), anyInt());
    }

    @Test
    @DisplayName("FNC-A-033 이벤트 판정이 예외로 실패해도 통계는 저장된 채로 두고 다른 구역도 계속 처리")
    void keepsSavedStatWhenEventJudgementFails() {
        minuteAccumulatorStore.add(ZONE_ID, MINUTE.withSecond(10), secondResult(40));
        minuteAccumulatorStore.add(OTHER_ZONE_ID, MINUTE.withSecond(10), secondResult(30));
        given(zoneService.findAreasByZoneIds(anySet()))
                .willReturn(Map.of(ZONE_ID, new BigDecimal("100.00"), OTHER_ZONE_ID, new BigDecimal("100.00")));
        willThrow(new IllegalStateException("판정 실패")).given(congestionEventService).judge(any(), any(), any(), any(), anyInt());

        assertThatCode(() -> minuteStatService.closeEndedMinutes(MINUTE.plusMinutes(1).plusSeconds(CLOSE_GRACE_SEC)))
                .doesNotThrowAnyException();

        verify(zoneStatMinuteRepository, times(2)).upsert(any());
        verify(congestionEventService, times(2)).judge(any(), any(), any(), any(), anyInt());
    }

    @Test
    @DisplayName("FNC-A-033 통계 저장이 실패하면 이벤트를 판정하지 않음")
    void doesNotJudgeWhenSaveFails() {
        minuteAccumulatorStore.add(ZONE_ID, MINUTE.withSecond(10), secondResult(40));
        given(zoneService.findAreasByZoneIds(anySet())).willReturn(Map.of(ZONE_ID, new BigDecimal("100.00")));
        willThrow(new DataAccessResourceFailureException("DB 연결 끊김")).given(zoneStatMinuteRepository).upsert(any());

        minuteStatService.closeEndedMinutes(MINUTE.plusMinutes(1).plusSeconds(CLOSE_GRACE_SEC));

        verify(congestionEventService, never()).judge(any(), any(), any(), any(), anyInt());
    }

    @Test
    @DisplayName("FNC-A-034 1분 통계를 저장한 직후 비교 시간(10분) 전 행의 인원·초 수와 함께 급증 이벤트를 판정")
    void judgesSurgeEventWithBaselineRow() {
        minuteAccumulatorStore.add(ZONE_ID, MINUTE.withSecond(10), secondResult(40));
        given(zoneService.findAreasByZoneIds(anySet())).willReturn(Map.of(ZONE_ID, new BigDecimal("100.00")));
        ZoneStatMinute baseline = baselineRow(ZONE_ID, MINUTE.minusMinutes(SURGE_WINDOW_MIN), "20.00", (short) 58);
        given(zoneStatMinuteRepository.findById(new ZoneStatMinuteId(ZONE_ID, MINUTE.minusMinutes(SURGE_WINDOW_MIN))))
                .willReturn(Optional.of(baseline));

        minuteStatService.closeEndedMinutes(MINUTE.plusMinutes(1).plusSeconds(CLOSE_GRACE_SEC));

        InOrder inOrder = inOrder(zoneStatMinuteRepository, surgeEventService);
        inOrder.verify(zoneStatMinuteRepository).upsert(any());
        inOrder.verify(surgeEventService).judge(
                eq(ZONE_ID),
                eq(MINUTE),
                any(),
                argThat(density -> density.compareTo(new BigDecimal("0.4000")) == 0),
                anyInt(),
                argThat(count -> count.compareTo(new BigDecimal("20.00")) == 0),
                eq(58));
    }

    @Test
    @DisplayName("FNC-A-034 비교 시간 전 행이 없으면 비교 값을 null로 넘김")
    void passesNullBaselineWhenRowMissing() {
        minuteAccumulatorStore.add(ZONE_ID, MINUTE.withSecond(10), secondResult(40));
        given(zoneService.findAreasByZoneIds(anySet())).willReturn(Map.of(ZONE_ID, new BigDecimal("100.00")));

        minuteStatService.closeEndedMinutes(MINUTE.plusMinutes(1).plusSeconds(CLOSE_GRACE_SEC));

        verify(surgeEventService).judge(eq(ZONE_ID), eq(MINUTE), any(), any(), anyInt(), isNull(), isNull());
    }

    @Test
    @DisplayName("FNC-A-034 급증 판정이 예외로 실패해도 통계는 저장된 채로 두고 다른 구역도 계속 처리")
    void keepsSavedStatWhenSurgeJudgementFails() {
        minuteAccumulatorStore.add(ZONE_ID, MINUTE.withSecond(10), secondResult(40));
        minuteAccumulatorStore.add(OTHER_ZONE_ID, MINUTE.withSecond(10), secondResult(30));
        given(zoneService.findAreasByZoneIds(anySet()))
                .willReturn(Map.of(ZONE_ID, new BigDecimal("100.00"), OTHER_ZONE_ID, new BigDecimal("100.00")));
        willThrow(new IllegalStateException("판정 실패"))
                .given(surgeEventService)
                .judge(any(), any(), any(), any(), anyInt(), any(), any());

        assertThatCode(() -> minuteStatService.closeEndedMinutes(MINUTE.plusMinutes(1).plusSeconds(CLOSE_GRACE_SEC)))
                .doesNotThrowAnyException();

        verify(zoneStatMinuteRepository, times(2)).upsert(any());
        verify(surgeEventService, times(2)).judge(any(), any(), any(), any(), anyInt(), any(), any());
    }

    @Test
    @DisplayName("FNC-A-034 혼잡 판정이 실패해도 급증 판정은 따로 실행")
    void judgesSurgeEventEvenWhenCongestionJudgementFails() {
        minuteAccumulatorStore.add(ZONE_ID, MINUTE.withSecond(10), secondResult(40));
        given(zoneService.findAreasByZoneIds(anySet())).willReturn(Map.of(ZONE_ID, new BigDecimal("100.00")));
        willThrow(new IllegalStateException("판정 실패")).given(congestionEventService).judge(any(), any(), any(), any(), anyInt());

        minuteStatService.closeEndedMinutes(MINUTE.plusMinutes(1).plusSeconds(CLOSE_GRACE_SEC));

        verify(surgeEventService).judge(eq(ZONE_ID), eq(MINUTE), any(), any(), anyInt(), any(), any());
    }

    @Test
    @DisplayName("FNC-A-034 통계 저장이 실패하면 급증 이벤트를 판정하지 않음")
    void doesNotJudgeSurgeWhenSaveFails() {
        minuteAccumulatorStore.add(ZONE_ID, MINUTE.withSecond(10), secondResult(40));
        given(zoneService.findAreasByZoneIds(anySet())).willReturn(Map.of(ZONE_ID, new BigDecimal("100.00")));
        willThrow(new DataAccessResourceFailureException("DB 연결 끊김")).given(zoneStatMinuteRepository).upsert(any());

        minuteStatService.closeEndedMinutes(MINUTE.plusMinutes(1).plusSeconds(CLOSE_GRACE_SEC));

        verify(surgeEventService, never()).judge(any(), any(), any(), any(), anyInt(), any(), any());
    }

    private ZoneStatMinute baselineRow(Long zoneId, LocalDateTime statAt, String countAvg, short sampleSec) {
        return ZoneStatMinute.builder()
                .zoneId(zoneId)
                .statAt(statAt)
                .countAvg(new BigDecimal(countAvg))
                .countMax(1)
                .countMin(0)
                .density(new BigDecimal("0.2000"))
                .sampleSec(sampleSec)
                .build();
    }

    private SecondResult secondResult(int personCount) {
        return new SecondResult(personCount, DirectionCounts.ZERO, DirectionCounts.ZERO, List.of());
    }
}
