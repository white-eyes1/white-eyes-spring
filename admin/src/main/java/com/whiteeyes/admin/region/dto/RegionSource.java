package com.whiteeyes.admin.region.dto;

import java.math.BigDecimal;

import com.whiteeyes.common.region.entity.RegionLevel;

/** 적재할 지역 하나. parentCode는 시·도일 때 null. */
public record RegionSource(
        String code,
        String parentCode,
        RegionLevel level,
        String name,
        String boundary,
        BigDecimal centerLat,
        BigDecimal centerLng) {}
