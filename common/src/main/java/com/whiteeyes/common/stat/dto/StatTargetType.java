package com.whiteeyes.common.stat.dto;

/**
 * 통계 시계열 대상의 종류(GET /api/v1/stats/series의 targetType). 구역 하나 또는 지역(시·도, 시·군·구, 읍·면·동).
 */
public enum StatTargetType {
    ZONE,
    REGION
}
