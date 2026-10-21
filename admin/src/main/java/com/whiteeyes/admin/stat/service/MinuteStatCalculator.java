package com.whiteeyes.admin.stat.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collections;
import java.util.List;

import com.whiteeyes.common.stat.entity.ZoneStatMinute;

/**
 * 마감한 누적기로 1분 통계 값을 계산한다(FNC-A-028 처리 순서 2).
 *
 * <p>반올림은 모두 HALF_UP이고, 0.005 같은 경계값이 이진수 오차로 내림되지 않게 BigDecimal로 계산한다.
 * density는 저장하는 count_avg(소수 2자리)로 나눠서, 표의 값만으로 다시 계산해도 같은 값이 나오게 한다
 * (지역 밀집도도 count_avg 합 ÷ 면적 합으로 계산함, conventions.md '혼잡 단계 판정').
 */
public final class MinuteStatCalculator {

    private MinuteStatCalculator() {
    }

    /**
     * @param accumulator 결과가 하나 이상 있는 누적기(결과가 없는 분은 누적기가 생기지 않음)
     * @param areaM2 마감 시점의 구역 면적. 면적을 바꾸면 이후 통계부터 적용된다
     */
    public static ZoneStatMinute calculate(MinuteAccumulator accumulator, BigDecimal areaM2) {
        List<Integer> personCounts = accumulator.getPersonCounts();
        int personCountSum = personCounts.stream().mapToInt(Integer::intValue).sum();
        BigDecimal countAvg = BigDecimal.valueOf(personCountSum)
                .divide(BigDecimal.valueOf(personCounts.size()), 2, RoundingMode.HALF_UP);
        DirectionCounts in = accumulator.getInTotal();
        DirectionCounts out = accumulator.getOutTotal();

        return ZoneStatMinute.builder()
                .zoneId(accumulator.getZoneId())
                .statAt(accumulator.getMinute())
                .countAvg(countAvg)
                // 분석 서버가 personCount를 이미 반올림한 정수로 보내므로(FNC-S-006) 그대로 쓴다
                .countMax(Collections.max(personCounts))
                .countMin(Collections.min(personCounts))
                .density(countAvg.divide(areaM2, 4, RoundingMode.HALF_UP))
                .inN(in.north())
                .inE(in.east())
                .inS(in.south())
                .inW(in.west())
                .outN(out.north())
                .outE(out.east())
                .outS(out.south())
                .outW(out.west())
                .dwellSumSec(BigDecimal.valueOf(accumulator.getDwellSumSec())
                        .setScale(0, RoundingMode.HALF_UP)
                        .intValueExact())
                .dwellCount(accumulator.getDwellCount())
                .sampleSec((short) accumulator.getSampleSec())
                .build();
    }
}
