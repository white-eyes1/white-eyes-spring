package com.whiteeyes.common.stat.dto;

import java.util.List;

/**
 * GET /api/v1/map/status 응답(FNC-C-009).
 *
 * @param unitType 항목의 단위: ZONE, SIGUNGU, EMD
 */
public record MapStatusResponse(String unitType, List<MapStatusItem> items) {
}
