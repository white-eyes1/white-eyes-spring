package com.whiteeyes.admin.region.dto;

import java.math.BigDecimal;

/** 구역 수정 결과(FNC-A-015): 수정된 구역의 현재 값. */
public record ZoneUpdateResponse(Long id, Long dongId, String name, BigDecimal areaM2) {}
