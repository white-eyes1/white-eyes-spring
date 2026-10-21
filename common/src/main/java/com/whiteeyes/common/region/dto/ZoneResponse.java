package com.whiteeyes.common.region.dto;

import com.whiteeyes.common.region.entity.Zone;
import java.math.BigDecimal;
import tools.jackson.databind.JsonNode;

/** GET /api/v1/zones 응답 항목. boundary는 문자열이 아니라 GeoJSON 객체로 내보낸다. */
public record ZoneResponse(
        Long id,
        Long dongId,
        String name,
        BigDecimal areaM2,
        BigDecimal centerLat,
        BigDecimal centerLng,
        JsonNode boundary,
        boolean hasCamera) {

    public static ZoneResponse of(Zone zone, JsonNode boundary, boolean hasCamera) {
        return new ZoneResponse(
                zone.getId(),
                zone.getDongId(),
                zone.getName(),
                zone.getAreaM2(),
                zone.getCenterLat(),
                zone.getCenterLng(),
                boundary,
                hasCamera);
    }
}
