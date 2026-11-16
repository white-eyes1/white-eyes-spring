package com.whiteeyes.common.stat.dto;

import java.util.List;

/**
 * GET /api/v1/map/status 응답(FNC-C-009).
 *
 * @param unitType 항목의 단위: ZONE, SIGUNGU, EMD
 */
// unitType을 같이 보내 화면이 항목을 구역 핀으로 그릴지 지역 영역으로 그릴지 정한다
public record MapStatusResponse(String unitType, List<MapStatusItem> items) {}
