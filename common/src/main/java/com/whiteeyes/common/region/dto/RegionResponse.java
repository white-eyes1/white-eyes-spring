package com.whiteeyes.common.region.dto;

import com.whiteeyes.common.region.entity.Region;
import com.whiteeyes.common.region.entity.RegionLevel;
import java.math.BigDecimal;
import tools.jackson.databind.JsonNode;

/** GET /api/v1/regions 응답 항목. boundary는 문자열이 아니라 GeoJSON 객체로 내보낸다. */
public record RegionResponse(
        Long id,
        RegionLevel level,
        String code,
        String name,
        BigDecimal centerLat,
        BigDecimal centerLng,
        JsonNode boundary) {

    public static RegionResponse of(Region region, JsonNode boundary) {
        return new RegionResponse(
                region.getId(),
                region.getLevel(),
                region.getCode(),
                region.getName(),
                region.getCenterLat(),
                region.getCenterLng(),
                boundary);
    }
}
