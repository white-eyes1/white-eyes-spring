package com.whiteeyes.common.region.dto;

import java.math.BigDecimal;

import tools.jackson.databind.JsonNode;

import com.whiteeyes.common.region.entity.Zone;

/** GET /api/v1/zones 응답 항목. boundary는 문자열이 아니라 GeoJSON 객체로 내보낸다. deleted는 삭제 표시된 구역이면 true(목록은 항상 false, 단건 조회에서만 true가 된다). */
// hasCamera는 이 구역에 삭제되지 않은 캠이 있는지, areaM2는 면적(㎡)이다
public record ZoneResponse(
        Long id,
        Long dongId,
        String name,
        BigDecimal areaM2,
        BigDecimal centerLat,
        BigDecimal centerLng,
        JsonNode boundary,
        boolean hasCamera,
        boolean deleted) {

    /** 구역 엔티티·파싱한 경계·캠 유무로 응답을 만든다. */
    public static ZoneResponse of(Zone zone, JsonNode boundary, boolean hasCamera) {
        return new ZoneResponse(
                zone.getId(),
                zone.getDongId(),
                zone.getName(),
                zone.getAreaM2(),
                zone.getCenterLat(),
                zone.getCenterLng(),
                boundary,
                hasCamera,
                // 삭제 시각이 있으면 삭제된 구역이다
                zone.getDeletedAt() != null);
    }
}
