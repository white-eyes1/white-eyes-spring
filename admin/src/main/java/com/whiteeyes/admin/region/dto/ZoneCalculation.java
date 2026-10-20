package com.whiteeyes.admin.region.dto;

import java.math.BigDecimal;

/** 다각형에서 서버가 계산한 값. centerLat·centerLng는 다각형 안의 점(JTS getInteriorPoint)이다. */
public record ZoneCalculation(double areaM2, Long dongId, String dongName, BigDecimal centerLat, BigDecimal centerLng) {}
