package com.whiteeyes.admin.region.dto;

import java.math.BigDecimal;

/** 다각형에서 서버가 계산한 값. centerLat·centerLng는 다각형 안의 점(JTS getInteriorPoint)이다. */
public record ZoneCalculation(
        // 미리보기 응답은 앞의 세 값만 쓰고, 구역 등록·수정은 중심 좌표까지 저장한다. 면적은 소수 1자리로 반올림된 값이다
        double areaM2, Long dongId, String dongName, BigDecimal centerLat, BigDecimal centerLng) {}
