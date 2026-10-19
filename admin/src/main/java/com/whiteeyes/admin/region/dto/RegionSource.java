package com.whiteeyes.admin.region.dto;

import com.whiteeyes.common.region.entity.RegionLevel;
import java.math.BigDecimal;

/** 경계 파일의 지역 하나. parentCode는 시일 때 null. */
public record RegionSource(
        String code,
        String parentCode,
        RegionLevel level,
        String name,
        String boundary,
        BigDecimal centerLat,
        BigDecimal centerLng) {}
