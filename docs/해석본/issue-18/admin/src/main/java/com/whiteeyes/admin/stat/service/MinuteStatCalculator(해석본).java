/*
 * [해석본] 원본: admin/src/main/java/com/whiteeyes/admin/stat/service/MinuteStatCalculator.java
 * 관련 이슈: #18(FNC-A-028)
 *
 * [무엇] 마감한 1분 바구니(MinuteAccumulator)와 구역 면적으로 zone_stat_minute 한 행의 값을 계산한다.
 *   FNC-A-028 처리 순서 2 "값 계산"에 해당한다.
 * [왜 따로 뺐나] 이슈 #18의 완료 기준이 "손으로 만든 60초 입력으로 값 검증"이다. 계산을 DB 저장과 섞어 두면
 *   이 검증에 DB와 스프링이 필요하다. 입력(바구니, 면적) → 출력(통계 행)만 있는 순수 계산으로 떼어 두면,
 *   테스트에서 60초 데이터를 넣고 결과 숫자만 바로 확인할 수 있다(MinuteStatCalculatorTest).
 * [사용자와 합의한 결정 5: 반올림 규칙] 명세에 반올림 방법이 없어서 정했고, PR에 '(검토 필요)'로 적는다.
 *   - count_avg: 소수 2자리 HALF_UP(일반적인 사사오입)
 *   - count_max/min: 그대로(분석 서버가 이미 반올림한 정수를 보냄)
 *   - density: '저장하는' count_avg ÷ 면적, 소수 4자리 HALF_UP
 *   - dwell_sum_sec: 정수로 HALF_UP
 */
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
// [해석] final + private 생성자 + static 메서드: 상태(필드)가 없는 계산 도구라 객체를 만들 필요가 없다.
//        스프링 빈(@Component)으로 만들지 않은 이유도 같다. 주입받을 것이 없고, 테스트에서 바로 부를 수 있다.
public final class MinuteStatCalculator {

    private MinuteStatCalculator() {
    }

    /**
     * @param accumulator 결과가 하나 이상 있는 누적기(결과가 없는 분은 누적기가 생기지 않음)
     * @param areaM2 마감 시점의 구역 면적. 면적을 바꾸면 이후 통계부터 적용된다
     */
    public static ZoneStatMinute calculate(MinuteAccumulator accumulator, BigDecimal areaM2) {
        // [해석] 초 순서로 정렬된 초별 인원 목록(같은 초가 다시 왔으면 #16이 마지막 값만 남겨 둠).
        List<Integer> personCounts = accumulator.getPersonCounts();
        // [해석] 평균 = 합 ÷ 받은 초 수. 60이 아니라 실제로 받은 초 수로 나눈다. 캠이 30초만 돌았으면 30초의 평균이다.
        //        누적기는 결과가 하나라도 있어야 생기므로 0으로 나눌 일은 없다.
        int personCountSum = personCounts.stream().mapToInt(Integer::intValue).sum();
        // [해석] BigDecimal.divide(나눌 수, 자릿수, 반올림 방법). double로 나눈 뒤 반올림하면 22.465가 22.464999…로
        //        저장돼 내림될 수 있다. BigDecimal은 10진수로 계산해서 이런 오차가 없다.
        BigDecimal countAvg = BigDecimal.valueOf(personCountSum)
                .divide(BigDecimal.valueOf(personCounts.size()), 2, RoundingMode.HALF_UP);
        // [해석] 1분 동안의 방향별 유입·유출 합. 합산은 #16의 MinuteAccumulator가 꺼낼 때 계산한다.
        DirectionCounts in = accumulator.getInTotal();
        DirectionCounts out = accumulator.getOutTotal();

        return ZoneStatMinute.builder()
                .zoneId(accumulator.getZoneId())
                .statAt(accumulator.getMinute())
                .countAvg(countAvg)
                // 분석 서버가 personCount를 이미 반올림한 정수로 보내므로(FNC-S-006) 그대로 쓴다
                // [해석] 명세의 "count_max/min=초별 최대/최소(반올림 정수)"는 이미 정수인 값이라 다시 반올림할 것이 없다.
                .countMax(Collections.max(personCounts))
                .countMin(Collections.min(personCounts))
                // [해석] 반올림 전 평균(22.4666…)이 아니라 저장하는 평균(22.47)으로 나눈다(결정 5).
                //        동·구·시 밀집도는 '저장된 count_avg 합 ÷ 면적 합'으로 계산하므로, 구역 밀집도도 같은 값에서
                //        출발해야 표의 숫자만으로 다시 계산했을 때 결과가 같다.
                .density(countAvg.divide(areaM2, 4, RoundingMode.HALF_UP))
                .inN(in.north())
                .inE(in.east())
                .inS(in.south())
                .inW(in.west())
                .outN(out.north())
                .outE(out.east())
                .outS(out.south())
                .outW(out.west())
                // [해석] 분석 서버는 체류 시간을 소수(예: 12.4초)로 보내는데 칼럼은 INT(초)다. 합을 먼저 구한 뒤
                //        한 번만 반올림한다(사람마다 반올림하면 오차가 쌓임). BigDecimal.valueOf(double)는 double을
                //        보이는 그대로의 10진수 문자열로 바꿔서 이진수 오차를 줄인다.
                //        intValueExact: int 범위를 넘으면 조용히 잘리지 않고 예외를 던진다(1분 합이라 넘을 일은 없음).
                .dwellSumSec(BigDecimal.valueOf(accumulator.getDwellSumSec())
                        .setScale(0, RoundingMode.HALF_UP)
                        .intValueExact())
                .dwellCount(accumulator.getDwellCount())
                // [해석] 받은 초 수는 최대 60이라 short(SMALLINT)에 안전하게 들어간다.
                .sampleSec((short) accumulator.getSampleSec())
                .build();
    }
}
