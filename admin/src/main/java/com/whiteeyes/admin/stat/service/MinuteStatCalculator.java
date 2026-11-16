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

    // 계산 함수만 있는 도구 클래스라 객체를 만들 수 없게 막는다
    private MinuteStatCalculator() {}

    /**
     * @param accumulator 결과가 하나 이상 있는 누적기(결과가 없는 분은 누적기가 생기지 않음)
     * @param areaM2 마감 시점의 구역 면적. 면적을 바꾸면 이후 통계부터 적용된다
     */
    public static ZoneStatMinute calculate(MinuteAccumulator accumulator, BigDecimal areaM2) {
        // 초별 인원 목록(받은 초만 있다). 평균·최대·최소의 재료다
        List<Integer> personCounts = accumulator.getPersonCounts();
        int personCountSum = personCounts.stream().mapToInt(Integer::intValue).sum();
        // 평균 인원 = 합계 ÷ 받은 초 수, 소수 2자리 HALF_UP. 60초 전체가 아니라 실제로 결과가 온 초 수로 나눈다
        BigDecimal countAvg = BigDecimal.valueOf(personCountSum)
                .divide(BigDecimal.valueOf(personCounts.size()), 2, RoundingMode.HALF_UP);
        // 분 전체의 방향별 출입 합계
        DirectionCounts in = accumulator.getInTotal();
        DirectionCounts out = accumulator.getOutTotal();

        // 아래 값들이 zone_stat_minute 한 행이 된다
        return ZoneStatMinute.builder()
                .zoneId(accumulator.getZoneId())
                .statAt(accumulator.getMinute())
                .countAvg(countAvg)
                // 분석 서버가 personCount를 이미 반올림한 정수로 보내므로(FNC-S-006) 그대로 쓴다
                .countMax(Collections.max(personCounts))
                .countMin(Collections.min(personCounts))
                // 밀집도 = 평균 인원 ÷ 면적(㎡), 소수 4자리. 저장하는 2자리 평균으로 나눠 표의 값만으로 재계산해도 같게 한다
                .density(countAvg.divide(areaM2, 4, RoundingMode.HALF_UP))
                .inN(in.north())
                .inE(in.east())
                .inS(in.south())
                .inW(in.west())
                .outN(out.north())
                .outE(out.east())
                .outS(out.south())
                .outW(out.west())
                // 체류 시간 합계는 초 단위 정수로 반올림해 저장한다. intValueExact는 정수 범위를 벗어나면 예외를 던진다
                .dwellSumSec(BigDecimal.valueOf(accumulator.getDwellSumSec())
                        .setScale(0, RoundingMode.HALF_UP)
                        .intValueExact())
                .dwellCount(accumulator.getDwellCount())
                // 결과가 실제로 온 초 수(0~60)라 short에 안전하게 들어간다. 혼잡·급증 이벤트 판정에도 함께 넘겨진다
                .sampleSec((short) accumulator.getSampleSec())
                .build();
    }
}
