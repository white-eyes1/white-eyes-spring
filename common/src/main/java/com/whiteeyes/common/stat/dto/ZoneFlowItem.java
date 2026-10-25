package com.whiteeyes.common.stat.dto;

/**
 * 구역 간 이동 하나(GET /api/v1/map/flows 응답의 한 줄, FNC-C-010).
 *
 * @param fromZoneId 나간 구역
 * @param toZoneId 그 방향의 이웃 구역. 이웃이 없으면 null(관측 범위 밖)
 * @param direction 나간 방향 N·E·S·W
 * @param count 기간의 그 방향 유출 합
 */
public record ZoneFlowItem(long fromZoneId, Long toZoneId, String direction, long count) {
}
