package com.whiteeyes.common.stat.entity;

import java.math.BigDecimal;

/**
 * 통계 한 행의 값(키 제외). 1분·시간·일 통계가 같은 칼럼을 쓰므로 묶을 때 한 모양으로 다룬다.
 * 시간별·일별은 dwell_sum_sec가 BIGINT, sample_sec가 INT라 1분 통계보다 넓은 타입으로 둔다(tables.md).
 */
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
        int sampleSec) {
}
