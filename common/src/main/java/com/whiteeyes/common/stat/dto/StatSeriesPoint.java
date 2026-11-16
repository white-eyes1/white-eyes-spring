package com.whiteeyes.common.stat.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;

/**
 * 통계 시계열의 점 하나(GET /api/v1/stats/series 응답 items의 한 줄, FNC-C-012 정상 결과).
 * 통계가 없거나 받은 초가 0인 구간은 at만 있고 값은 null이다(그래프 끊김, #25에서 정함).
 *
 * @param at 구간 시작 시각(+09:00)
 * @param inflow 방향별 유입(N·E·S·W). 지역은 지역 안 구역끼리 주고받은 이동을 뺀 값
 * @param dwellAvgSec 평균 체류 시간(초). 체류를 잰 사람이 없으면 null
 * @param sampleSec 결과가 들어온 초 수. 지역은 합산한 구역 중 가장 큰 값
 * @param isDemo 시연용 데이터가 섞인 점이면 true
 */
// 필드 요약: countAvg/Max/Min 구간 평균·최대·최소 인원, density 평균 밀집도, level 혼잡 단계,
// inflow·outflow 방향(N·E·S·W)별 합, outflow는 유입과 같은 규칙
public record StatSeriesPoint(
        OffsetDateTime at,
        BigDecimal countAvg,
        Integer countMax,
        Integer countMin,
        BigDecimal density,
        Integer level,
        Map<String, Integer> inflow,
        Map<String, Integer> outflow,
        BigDecimal dwellAvgSec,
        int sampleSec,
        boolean isDemo) {

    /** 통계가 없는 구간의 점. */
    public static StatSeriesPoint empty(OffsetDateTime at) {
        // 값은 모두 null로 두고 sampleSec만 0. 응답은 모든 구간을 빠짐없이 담아야 해서 빈 구간도 점으로 만든다
        return new StatSeriesPoint(at, null, null, null, null, null, null, null, null, 0, false);
    }
}
