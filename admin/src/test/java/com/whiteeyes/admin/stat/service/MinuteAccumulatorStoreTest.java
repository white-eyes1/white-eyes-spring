package com.whiteeyes.admin.stat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MinuteAccumulatorStoreTest {

    private static final long ZONE_ID = 3L;
    private static final LocalDateTime MINUTE = LocalDateTime.of(2026, 10, 12, 14, 3);

    private final MinuteAccumulatorStore store = new MinuteAccumulatorStore();

    @Test
    @DisplayName("FNC-A-024 같은 초가 두 번 오면 한 번만 반영")
    void countsSameSecondOnce() {
        store.add(ZONE_ID, MINUTE.withSecond(5), secondResult(40, new DirectionCounts(1, 0, 0, 2), List.of(10.0)));
        store.add(ZONE_ID, MINUTE.withSecond(5), secondResult(42, new DirectionCounts(1, 0, 0, 2), List.of(10.0)));

        MinuteAccumulator accumulator = store.closeMinute(ZONE_ID, MINUTE).orElseThrow();

        assertThat(accumulator.getSampleSec()).isEqualTo(1);
        assertThat(accumulator.getPersonCounts()).containsExactly(42);
        assertThat(accumulator.getInTotal()).isEqualTo(new DirectionCounts(1, 0, 0, 2));
        assertThat(accumulator.getDwellCount()).isEqualTo(1);
        assertThat(accumulator.getDwellSumSec()).isEqualTo(10.0);
    }

    @Test
    @DisplayName("FNC-A-024 서로 다른 초는 모두 더함")
    void sumsDifferentSeconds() {
        store.add(ZONE_ID, MINUTE.withSecond(6), secondResult(30, new DirectionCounts(0, 1, 0, 0), List.of(5.0)));
        store.add(ZONE_ID, MINUTE.withSecond(5), secondResult(20, new DirectionCounts(1, 0, 0, 0), List.of(305.2, 41.0)));

        MinuteAccumulator accumulator = store.closeMinute(ZONE_ID, MINUTE).orElseThrow();

        assertThat(accumulator.getZoneId()).isEqualTo(ZONE_ID);
        assertThat(accumulator.getMinute()).isEqualTo(MINUTE);
        assertThat(accumulator.getSampleSec()).isEqualTo(2);
        assertThat(accumulator.getPersonCounts()).containsExactly(20, 30);
        assertThat(accumulator.getInTotal()).isEqualTo(new DirectionCounts(1, 1, 0, 0));
        assertThat(accumulator.getOutTotal()).isEqualTo(DirectionCounts.ZERO);
        assertThat(accumulator.getDwellCount()).isEqualTo(3);
        assertThat(accumulator.getDwellSumSec()).isCloseTo(351.2, within(1e-9));
    }

    @Test
    @DisplayName("FNC-A-024 이미 마감된 분에 늦게 도착한 결과는 버림")
    void dropsResultForClosedMinute() {
        store.add(ZONE_ID, MINUTE.withSecond(5), secondResult(40));
        store.closeMinute(ZONE_ID, MINUTE);

        boolean added = store.add(ZONE_ID, MINUTE.withSecond(59), secondResult(41));

        assertThat(added).isFalse();
        assertThat(store.closeMinute(ZONE_ID, MINUTE)).isEmpty();
    }

    @Test
    @DisplayName("FNC-A-024 마감은 구역·분 단위라 다른 구역과 다음 분은 받음")
    void acceptsOtherZoneAndNextMinuteAfterClose() {
        store.closeMinute(ZONE_ID, MINUTE);

        assertThat(store.add(4L, MINUTE.withSecond(5), secondResult(10))).isTrue();
        assertThat(store.add(ZONE_ID, MINUTE.plusMinutes(1), secondResult(10))).isTrue();
    }

    @Test
    @DisplayName("FNC-A-024 마감 기록이 없는 지난 분의 결과는 받음(관리자 앱 재시작 뒤 재전송분)")
    void acceptsPastMinuteWithoutCloseRecord() {
        boolean added = store.add(ZONE_ID, MINUTE.minusMinutes(3).withSecond(10), secondResult(40));

        assertThat(added).isTrue();
    }

    @Test
    @DisplayName("FNC-A-024 마감 기록은 10분 동안 유지하고 그보다 오래된 것은 정리")
    void prunesCloseRecordsOlderThanRetention() {
        LocalDateTime keptMinute = MINUTE.plusMinutes(1);
        store.closeMinute(ZONE_ID, MINUTE);
        store.closeMinute(ZONE_ID, keptMinute);

        store.closeMinute(ZONE_ID, MINUTE.plusMinutes(11));

        assertThat(store.add(ZONE_ID, MINUTE.withSecond(10), secondResult(40))).isTrue();
        assertThat(store.add(ZONE_ID, keptMinute.withSecond(10), secondResult(40))).isFalse();
    }

    @Test
    @DisplayName("FNC-A-024 결과가 하나도 없는 분을 마감하면 빈 값")
    void returnsEmptyWhenNoResults() {
        assertThat(store.closeMinute(ZONE_ID, MINUTE)).isEmpty();
    }

    @Test
    @DisplayName("FNC-A-028 분 끝 시각이 cutoff 이하인 누적기만 마감하고 분·구역 순서로 돌려줌")
    void closesOnlyMinutesEndedByCutoff() {
        store.add(4L, MINUTE.withSecond(10), secondResult(40));
        store.add(ZONE_ID, MINUTE.withSecond(10), secondResult(30));
        store.add(ZONE_ID, MINUTE.plusMinutes(1).withSecond(10), secondResult(20));

        assertThat(store.closeMinutesEndedBy(MINUTE.plusMinutes(1).minusSeconds(1))).isEmpty();

        List<MinuteAccumulator> closed = store.closeMinutesEndedBy(MINUTE.plusMinutes(1));

        assertThat(closed).extracting(MinuteAccumulator::getZoneId).containsExactly(ZONE_ID, 4L);
        assertThat(closed).extracting(MinuteAccumulator::getMinute).containsOnly(MINUTE);
        assertThat(store.add(ZONE_ID, MINUTE.withSecond(59), secondResult(41))).isFalse();
        assertThat(store.closeMinute(ZONE_ID, MINUTE.plusMinutes(1))).isPresent();
    }

    private SecondResult secondResult(int personCount) {
        return secondResult(personCount, DirectionCounts.ZERO, List.of());
    }

    private SecondResult secondResult(int personCount, DirectionCounts in, List<Double> dwellSec) {
        return new SecondResult(personCount, in, DirectionCounts.ZERO, dwellSec);
    }
}
