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
    private static final long CLOSE_GRACE_SEC = 5;

    @Mock
    private ZoneQueryService zoneQueryService;

    @Mock
    private ZoneStatMinuteRepository zoneStatMinuteRepository;

    private MinuteAccumulatorStore minuteAccumulatorStore;
    private MinuteStatService minuteStatService;

    @BeforeEach
    void setUp() {
        minuteAccumulatorStore = new MinuteAccumulatorStore();
        minuteStatService = new MinuteStatService(
                minuteAccumulatorStore, zoneQueryService, zoneStatMinuteRepository, CLOSE_GRACE_SEC);
    }

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

    @Test
    @DisplayName("FNC-A-028 한 구역 저장이 실패해도 다른 구역은 저장하고, 실패한 누적기는 버림")
    void continuesWhenOneSaveFails() {
        minuteAccumulatorStore.add(ZONE_ID, MINUTE.withSecond(10), secondResult(40));
        minuteAccumulatorStore.add(OTHER_ZONE_ID, MINUTE.withSecond(10), secondResult(30));
        given(zoneQueryService.findAreasByZoneIds(anySet()))
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
        given(zoneQueryService.findAreasByZoneIds(anySet()))
                .willReturn(Map.of(OTHER_ZONE_ID, new BigDecimal("100.00")));

        minuteStatService.closeEndedMinutes(MINUTE.plusMinutes(1).plusSeconds(CLOSE_GRACE_SEC));

        verify(zoneStatMinuteRepository).upsert(argThat(stat -> stat.getZoneId() == OTHER_ZONE_ID));
        verify(zoneStatMinuteRepository, times(1)).upsert(any());
    }

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
