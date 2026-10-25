package com.whiteeyes.common.stat.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * GET /api/v1/stats/children 응답(FNC-C-014).
 *
 * @param series 하위 단위별 계열. 최대 10개
 * @param omitted 10개를 넘어 빠진 하위 단위 수('나머지 n개 생략')
 */
public record StatChildrenResponse(List<Series> series, int omitted) {

    /**
     * 하위 단위 하나의 계열.
     *
     * @param id 하위 지역 번호, 상위가 읍·면·동이면 구역 번호
     * @param points 조회 기간의 모든 구간(시각 순). 통계가 없는 구간은 value가 null
     */
    public record Series(long id, String name, List<Point> points) {
    }

    /**
     * @param at 구간 시작 시각(+09:00)
     * @param value 고른 항목의 값. 유입·유출은 네 방향 합
     */
    public record Point(OffsetDateTime at, BigDecimal value) {
    }
}
