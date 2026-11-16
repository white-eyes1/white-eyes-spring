package com.whiteeyes.common.region.dto;

import java.math.BigDecimal;

import tools.jackson.databind.JsonNode;

import com.whiteeyes.common.region.entity.Region;
import com.whiteeyes.common.region.entity.RegionLevel;

/** GET /api/v1/regions 응답 항목. boundary는 문자열이 아니라 GeoJSON 객체로 내보낸다. */
// boundary(GeoJSON)는 DB에 문자열로 저장돼 있어, 문자열 그대로 보내면 JSON 안에 이스케이프된 문자열이 되므로 JsonNode(객체)로 바꿔 받는다
public record RegionResponse(
        Long id,
        RegionLevel level,
        String code,
        String name,
        BigDecimal centerLat,
        BigDecimal centerLng,
        JsonNode boundary) {

    /** 엔티티와 파싱한 경계(GeoJSON)로 응답을 만든다. 파싱은 서비스가 해서 넘긴다. */
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
