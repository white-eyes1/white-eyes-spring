package com.whiteeyes.common.level.dto;

import java.math.BigDecimal;

/**
 * 혼잡 단계 하나의 기준(GET /api/v1/meta/levels 응답 항목).
 *
 * @param minDensity 이 단계가 시작되는 밀집도(명/㎡). 정상은 0
 */
public record LevelResponse(int level, String name, String color, BigDecimal minDensity) {
}
