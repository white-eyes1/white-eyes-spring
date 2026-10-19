/*
 * [해석본] 원본: admin/src/test/java/com/whiteeyes/admin/stat/service/MinuteAccumulatorStoreTest.java
 * 관련 이슈: #16(FNC-A-024)
 *
 * [무엇] 누적기 저장소의 단위 테스트. 이슈의 완료 기준 두 개가 여기서 확인된다.
 *   - "같은 초가 두 번 오면 한 번만 반영" → countsSameSecondOnce
 *   - "늦은 결과 버림" → dropsResultForClosedMinute
 * [왜 Spring 없이 new로 만드나] 저장소는 다른 빈에 의존하지 않는 순수 자바 객체다. Spring 컨텍스트를 띄우지 않으면
 *   테스트가 빠르고, 실패했을 때 원인이 이 클래스 하나로 좁혀진다.
 * [이름 규칙] @DisplayName에 기능 ID(FNC-A-024)와 한국어 설명을 쓴다(code-conventions 6장). 메서드 이름은 영어로 짧게.
 * [구조] 각 테스트는 '준비(given) → 실행(when) → 확인(then)'을 빈 줄로 나눠 읽기 쉽게 했다.
 */
package com.whiteeyes.admin.stat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MinuteAccumulatorStoreTest {

    // [해석] 모든 테스트가 같은 구역·분을 쓰게 상수로 뺐다. 값 자체(3, 14:03)에는 의미가 없고 api/analyzer.md 예시와 비슷하게 골랐다.
    private static final long ZONE_ID = 3L;
    private static final LocalDateTime MINUTE = LocalDateTime.of(2026, 10, 12, 14, 3);

    // [해석] JUnit은 테스트 메서드마다 테스트 클래스를 새로 만든다. 그래서 store도 테스트마다 새것이라 테스트끼리 섞이지 않는다.
    private final MinuteAccumulatorStore store = new MinuteAccumulatorStore();

    // [해석] 완료 기준 1. 같은 초(14:03:05)에 인원 40 → 42로 재전송된 상황.
    //        확인하는 것: 받은 초 수가 2가 아니라 1, 인원은 마지막 값(42), 유입·체류도 두 번 더해지지 않음.
    //        인원만 확인하면 'in/out·dwell도 한 번만 센다'는 결정 2를 검증하지 못하므로 넷을 다 본다.
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

    // [해석] 위 테스트의 반대편: 서로 다른 초는 정상적으로 모두 더해지는지. 일부러 6초를 먼저, 5초를 나중에 넣어서
    //        getPersonCounts가 '넣은 순서'가 아니라 '초 순서'(20, 30)로 나오는지도 함께 본다(TreeMap 정렬 확인).
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
        // [해석] 소수(double) 덧셈은 305.2 + 41.0 + 5.0이 정확히 351.2가 아닐 수 있다(2진수 표현 오차).
        //        그래서 '거의 같음'(오차 0.000000001 이내)으로 비교한다. isEqualTo로 쓰면 환경에 따라 실패할 수 있다.
        assertThat(accumulator.getDwellSumSec()).isCloseTo(351.2, within(1e-9));
    }

    // [해석] 완료 기준 2. 14:03을 마감한 뒤 14:03:59 결과가 늦게 도착한 상황.
    //        add가 false(버림)를 돌려주고, 다시 마감해 봐도 바구니가 없어야(=새로 만들어지지 않았어야) 한다.
    //        두 번째 확인이 중요하다: 이게 깨지면 #18이 작은 바구니로 정상 행을 덮어쓰는 사고가 난다.
    @Test
    @DisplayName("FNC-A-024 이미 마감된 분에 늦게 도착한 결과는 버림")
    void dropsResultForClosedMinute() {
        store.add(ZONE_ID, MINUTE.withSecond(5), secondResult(40));
        store.closeMinute(ZONE_ID, MINUTE);

        boolean added = store.add(ZONE_ID, MINUTE.withSecond(59), secondResult(41));

        assertThat(added).isFalse();
        assertThat(store.closeMinute(ZONE_ID, MINUTE)).isEmpty();
    }

    // [해석] 마감이 너무 넓게 걸리지 않는지. 구역 3의 14:03을 마감해도 구역 4의 14:03, 구역 3의 14:04는 받아야 한다.
    @Test
    @DisplayName("FNC-A-024 마감은 구역·분 단위라 다른 구역과 다음 분은 받음")
    void acceptsOtherZoneAndNextMinuteAfterClose() {
        store.closeMinute(ZONE_ID, MINUTE);

        assertThat(store.add(4L, MINUTE.withSecond(5), secondResult(10))).isTrue();
        assertThat(store.add(ZONE_ID, MINUTE.plusMinutes(1), secondResult(10))).isTrue();
    }

    // [해석] 결정 1(기록으로 판정)의 핵심 시나리오. 새로 만든 저장소 = 관리자 앱이 막 재시작한 상태(마감 기록 없음).
    //        3분 전 결과가 와도 받아야 FNC-S-006 "2분 껐다 켜면 채워짐"이 성립한다.
    //        시각으로 판정하는 방식이었다면 이 테스트는 실패한다.
    @Test
    @DisplayName("FNC-A-024 마감 기록이 없는 지난 분의 결과는 받음(관리자 앱 재시작 뒤 재전송분)")
    void acceptsPastMinuteWithoutCloseRecord() {
        boolean added = store.add(ZONE_ID, MINUTE.minusMinutes(3).withSecond(10), secondResult(40));

        assertThat(added).isTrue();
    }

    // [해석] 10분 보관의 경계를 확인한다. 14:03, 14:04를 마감한 뒤 14:14를 마감하면 기준은 14:14 - 10분 = 14:04.
    //        - 14:03은 14:04보다 이전 → 기록이 지워져 다시 받음(true)
    //        - 14:04는 기준과 같음(isBefore가 아님) → 기록 유지라 여전히 버림(false)
    //        경계 바로 안과 밖을 둘 다 봐야 '10분'이 정확히 지켜지는지 알 수 있다.
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

    // [해석] 결과가 하나도 없던 분은 빈 값. #18은 이것을 보고 행을 만들지 않는다(FNC-A-028 "빈 구간").
    @Test
    @DisplayName("FNC-A-024 결과가 하나도 없는 분을 마감하면 빈 값")
    void returnsEmptyWhenNoResults() {
        assertThat(store.closeMinute(ZONE_ID, MINUTE)).isEmpty();
    }

    // [해석] 테스트마다 반복되는 '1초 결과 만들기'를 짧게 쓰려는 도우미. 인원만 중요한 테스트는 첫 번째를,
    //        유입·체류까지 보는 테스트는 두 번째를 쓴다. 유출(out)은 sumsDifferentSeconds에서 ZERO인지만 보면 되므로 고정.
    private SecondResult secondResult(int personCount) {
        return secondResult(personCount, DirectionCounts.ZERO, List.of());
    }

    private SecondResult secondResult(int personCount, DirectionCounts in, List<Double> dwellSec) {
        return new SecondResult(personCount, in, DirectionCounts.ZERO, dwellSec);
    }
}
