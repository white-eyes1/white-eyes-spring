package com.whiteeyes.admin.region.dto;

/** 구역 미리보기 결과(FNC-A-013). 서버가 계산한 면적(㎡)과, 다각형 내부 점이 속한 읍·면·동의 번호·이름. */
public record ZonePreviewResponse(double areaM2, Long dongId, String dongName) {}
