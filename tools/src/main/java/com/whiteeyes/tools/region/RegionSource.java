package com.whiteeyes.tools.region;

import java.math.BigDecimal;

import com.whiteeyes.common.region.entity.RegionLevel;

/** 적재할 지역 하나. parentCode는 시·도일 때 null. */
public record RegionSource(
        // code: 행정 코드(시·도 2자리, 시·군·구 5자리, 읍·면·동 8자리), level: 단계, name: 이름, boundary: 경계 GeoJSON 문자열,
        // centerLat/centerLng: 지도에서 이동할 대표 점.
        String code,
        String parentCode,
        RegionLevel level,
        String name,
        String boundary,
        BigDecimal centerLat,
        BigDecimal centerLng) {}
