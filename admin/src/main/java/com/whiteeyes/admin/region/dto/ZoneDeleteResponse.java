package com.whiteeyes.admin.region.dto;

/** 구역 삭제 결과(FNC-A-017). mode는 이력이 없어 실제로 지웠으면 HARD, 이력이 있어 삭제 표시만 했으면 SOFT. */
public record ZoneDeleteResponse(String mode) {}
