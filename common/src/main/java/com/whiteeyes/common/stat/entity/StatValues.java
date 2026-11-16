package com.whiteeyes.common.stat.entity;

import java.math.BigDecimal;

/**
 * 통계 한 행의 값(키 제외). 1분·시간·일 통계가 같은 칼럼을 쓰므로 묶을 때 한 모양으로 다룬다.
 * 시간별·일별은 dwell_sum_sec가 BIGINT, sample_sec가 INT라 1분 통계보다 넓은 타입으로 둔다(tables.md).
 */
// 필드 요약: countAvg/countMax/countMin 평균·최대·최소 인원, density 밀집도, inN~inW·outN~outW 방향(북·동·남·서)별 유입·유출,
// dwellSumSec 체류 시간 합(초), dwellCount 체류를 잰 사람 수(평균 체류 = 합 / 사람 수), sampleSec 결과가 들어온 초 수
// 엔티티(ZoneStatMinute·ZoneStatHourly·ZoneStatDaily)와 StatRollupCalculator 사이에서 값을 주고받는 용도로, 영속 대상은 아니다
public record StatValues(
        BigDecimal countAvg,
        int countMax,
        int countMin,
        BigDecimal density,
        int inN,
        int inE,
        int inS,
        int inW,
        int outN,
        int outE,
        int outS,
        int outW,
        long dwellSumSec,
        int dwellCount,
        int sampleSec) {}
