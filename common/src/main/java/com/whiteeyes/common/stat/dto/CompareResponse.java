package com.whiteeyes.common.stat.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * GET /api/v1/compare 응답(FNC-C-020).
 *
 * @param cells 168칸 전부(월 0시부터 일 23시까지 순서)
 * @param totalRate 비교 가능한 칸 전체의 증감률(%). 비교 가능한 칸이 없으면 null
 */
public record CompareResponse(List<CompareCell> cells, BigDecimal totalRate) {
}
