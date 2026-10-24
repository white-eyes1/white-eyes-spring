package com.whiteeyes.common.stat.dto;

/**
 * 통계 엑셀 다운로드의 범위(GET /api/v1/stats/export의 scope, FNC-C-023).
 * 구역 하나, 지역(시·도, 시·군·구, 읍·면·동), 전체(삭제되지 않은 모든 구역).
 */
public enum StatExportScope {
    ZONE,
    REGION,
    ALL
}
