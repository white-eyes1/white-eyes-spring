package com.whiteeyes.common.stat.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * GET /api/v1/map/heatmap 응답(FNC-C-011).
 *
 * @param units 하위 단위 번호(지역 번호, 상위가 읍·면·동이면 구역 번호). 목록 순서
 * @param values 단위 번호(문자열) → 0~23시 평균 24개. 데이터 없는 시간은 null(회색)
 * @param min 모든 칸 중 가장 작은 값(색 정규화). 값이 하나도 없으면 null
 * @param max 모든 칸 중 가장 큰 값
 */
public record HeatmapResponse(List<Long> units, Map<String, List<BigDecimal>> values, BigDecimal min, BigDecimal max) {
}
