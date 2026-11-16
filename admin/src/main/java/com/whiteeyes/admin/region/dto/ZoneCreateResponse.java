package com.whiteeyes.admin.region.dto;

/** 구역 등록 결과(FNC-A-014). 새 구역 번호·소속 읍·면·동 번호·저장된 이름·서버가 계산한 면적(㎡). */
public record ZoneCreateResponse(Long id, Long dongId, String name, double areaM2) {}
