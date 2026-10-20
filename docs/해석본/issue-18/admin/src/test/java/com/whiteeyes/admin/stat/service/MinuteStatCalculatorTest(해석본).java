/*
 * [해석본] 원본: admin/src/test/java/com/whiteeyes/admin/stat/service/MinuteStatCalculatorTest.java
 * 관련 이슈: #18(FNC-A-028)
 *
 * [무엇] 1분 통계 값 계산의 단위 테스트. 이슈 #18의 완료 기준 "손으로 만든 60초 입력으로 값 검증"이 여기서 확인된다.
 * [어떻게] 60초 동안의 1초 결과를 사람이 계산할 수 있는 규칙으로 만들고, 기대값을 주석에 손으로 계산해 적었다.
 *   코드로 기대값을 계산하면(예: 평균을 테스트에서도 같은 식으로 구함) 계산식이 틀려도 테스트가 함께 틀려 통과해 버린다.
 *   그래서 기대값은 숫자 그대로(22.47, 0.1498 …) 적는다.
 * [왜 DB 없이] MinuteStatCalculator가 입력 → 출력만 있는 순수 계산이라 Spring도 DB도 필요 없다. 빠르고, 틀리면 원인이 계산뿐이다.
 */
package com.whiteeyes.admin.stat.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.whiteeyes.common.stat.entity.ZoneStatMinute;

class MinuteStatCalculatorTest {

    private static final long ZONE_ID = 3L;
    private static final LocalDateTime MINUTE = LocalDateTime.of(2026, 10, 12, 14, 3);

    // [해석] 완료 기준. 모든 칼럼(17개)을 한 번에 확인한다. 최대·최저가 중간(10초·45초)에 있게 해서
    //        '첫 값·마지막 값'을 최대·최저로 잘못 쓰는 실수도 잡는다. 평균은 일부러 나누어떨어지지 않게(22.4666…) 만들어
    //        반올림까지 함께 확인한다.
    @Test
    @DisplayName("FNC-A-028 손으로 만든 60초 입력으로 값 검증")
    void calculatesHandMadeSixtySeconds() {
        // 인원: 0~29초 20명, 30~59초 25명. 10초만 31명(최대), 45초만 12명(최저)
        //   합 = 20×29 + 31 + 25×29 + 12 = 1348 → 평균 1348 ÷ 60 = 22.4666… → 22.47
        // 유입: 0·10·20·30·40·50초에 북쪽 1명씩(6), 15초에 동쪽 2명
        // 유출: 5·25·45초에 남쪽 1명씩(3), 59초에 서쪽 4명
        // 체류: 10초에 12.4초·30.0초, 40초에 7.3초 → 합 49.7 → 50, 3명
        // [해석] MinuteAccumulator의 생성자와 put은 package-private이다. 이 테스트가 같은 패키지(stat.service)에 있어서
        //        #16의 수신 경로를 거치지 않고 바구니를 직접 채울 수 있다.
        MinuteAccumulator accumulator = new MinuteAccumulator(ZONE_ID, MINUTE);
        for (int second = 0; second < 60; second++) {
            accumulator.put(MINUTE.withSecond(second), handMadeResult(second));
        }

        ZoneStatMinute stat = MinuteStatCalculator.calculate(accumulator, new BigDecimal("150.00"));

        assertThat(stat.getZoneId()).isEqualTo(ZONE_ID);
        assertThat(stat.getStatAt()).isEqualTo(MINUTE);
        // [해석] BigDecimal은 isEqualTo 대신 isEqualByComparingTo로 비교한다. isEqualTo는 자릿수까지 비교해서
        //        22.47과 22.470을 다르다고 본다. 값이 같은지만 보려면 compareTo 기준이 맞다.
        assertThat(stat.getCountAvg()).isEqualByComparingTo("22.47");
        assertThat(stat.getCountMax()).isEqualTo(31);
        assertThat(stat.getCountMin()).isEqualTo(12);
        // 22.47 ÷ 150 = 0.1498
        assertThat(stat.getDensity()).isEqualByComparingTo("0.1498");
        // [해석] 방향 네 칸을 목록으로 묶어 순서(N, E, S, W)까지 한 줄로 비교한다. 칸을 서로 바꿔 넣는 실수를 잡는다.
        assertThat(List.of(stat.getInN(), stat.getInE(), stat.getInS(), stat.getInW())).containsExactly(6, 2, 0, 0);
        assertThat(List.of(stat.getOutN(), stat.getOutE(), stat.getOutS(), stat.getOutW()))
                .containsExactly(0, 0, 3, 4);
        assertThat(stat.getDwellSumSec()).isEqualTo(50);
        assertThat(stat.getDwellCount()).isEqualTo(3);
        assertThat(stat.getSampleSec()).isEqualTo((short) 60);
    }

    // [해석] 사용자와 합의한 결정 5(반올림 규칙)를 경계값으로 확인한다.
    //        - 0.125는 HALF_UP이면 0.13, 은행가 반올림(HALF_EVEN)이면 0.12. 규칙을 바꾸면 이 테스트가 깨진다.
    //        - density를 저장된 0.13으로 나누면 0.0433, 반올림 전 0.125로 나누면 0.0417이라 어느 쪽으로 계산했는지 구별된다.
    //        - 체류 2.5초는 HALF_UP이면 3.
    //        8초만 들어온 분이라 평균의 분모가 60이 아니라 8인지(sample_sec = 8)도 함께 확인한다.
    @Test
    @DisplayName("FNC-A-028 반올림은 HALF_UP이고 density는 저장하는 count_avg로 계산")
    void roundsHalfUpAndUsesStoredCountAvgForDensity() {
        // 8초만 들어온 분: 인원 1, 0, 0, 0, 0, 0, 0, 0 → 평균 0.125 → 0.13(HALF_EVEN이면 0.12)
        // density: 0.13 ÷ 3 = 0.04333… → 0.0433 (반올림 전 평균으로 나누면 0.0417)
        // 체류: 1.25 + 1.25 = 2.5 → 3
        MinuteAccumulator accumulator = new MinuteAccumulator(ZONE_ID, MINUTE);
        accumulator.put(MINUTE.withSecond(0), result(1, DirectionCounts.ZERO, DirectionCounts.ZERO, List.of(1.25, 1.25)));
        for (int second = 1; second < 8; second++) {
            accumulator.put(MINUTE.withSecond(second), result(0, DirectionCounts.ZERO, DirectionCounts.ZERO, List.of()));
        }

        ZoneStatMinute stat = MinuteStatCalculator.calculate(accumulator, new BigDecimal("3.00"));

        assertThat(stat.getCountAvg()).isEqualByComparingTo("0.13");
        assertThat(stat.getDensity()).isEqualByComparingTo("0.0433");
        assertThat(stat.getDwellSumSec()).isEqualTo(3);
        assertThat(stat.getSampleSec()).isEqualTo((short) 8);
    }

    // [해석] 위 주석에 적은 60초 규칙을 그대로 코드로 옮긴 것. 초(second)를 받아 그 초의 1초 결과를 만든다.
    //        규칙이 주석과 코드 두 곳에 있으므로, 하나를 바꾸면 다른 하나도 함께 고쳐야 한다.
    private SecondResult handMadeResult(int second) {
        int personCount = second < 30 ? 20 : 25;
        if (second == 10) {
            personCount = 31;
        } else if (second == 45) {
            personCount = 12;
        }

        DirectionCounts in = DirectionCounts.ZERO;
        if (second % 10 == 0) {
            in = new DirectionCounts(1, 0, 0, 0);
        } else if (second == 15) {
            in = new DirectionCounts(0, 2, 0, 0);
        }

        DirectionCounts out = DirectionCounts.ZERO;
        if (second == 5 || second == 25 || second == 45) {
            out = new DirectionCounts(0, 0, 1, 0);
        } else if (second == 59) {
            out = new DirectionCounts(0, 0, 0, 4);
        }

        List<Double> dwellSec = List.of();
        if (second == 10) {
            dwellSec = List.of(12.4, 30.0);
        } else if (second == 40) {
            dwellSec = List.of(7.3);
        }
        return result(personCount, in, out, dwellSec);
    }

    private SecondResult result(int personCount, DirectionCounts in, DirectionCounts out, List<Double> dwellSec) {
        return new SecondResult(personCount, in, out, dwellSec);
    }
}
