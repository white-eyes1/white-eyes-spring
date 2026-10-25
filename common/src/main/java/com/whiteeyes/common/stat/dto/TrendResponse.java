package com.whiteeyes.common.stat.dto;

import java.math.BigDecimal;

/**
 * GET /api/v1/dashboard/trend 응답(FNC-C-019). 대상 기간은 어제까지 7일이다.
 *
 * @param weekRate 7일 전 같은 7일 대비 증감률(%). 기준 기간 데이터가 없으면 null('-')
 * @param monthRate 28일 전 같은 7일 대비 증감률(%)
 * @param yearRate 364일 전 같은 7일 대비 증감률(%)
 */
public record TrendResponse(BigDecimal weekRate, BigDecimal monthRate, BigDecimal yearRate) {
}
