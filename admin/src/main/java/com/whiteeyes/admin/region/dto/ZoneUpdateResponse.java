package com.whiteeyes.admin.region.dto;

import java.math.BigDecimal;

/** 구역 수정 결과(FNC-A-015): 수정된 구역의 현재 값. */
// 다각형을 바꾸지 않은 요청이면 areaM2는 기존 면적 그대로다
public record ZoneUpdateResponse(Long id, Long dongId, String name, BigDecimal areaM2) {}
