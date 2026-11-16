package com.whiteeyes.admin.region.dto;

/** 구역 삭제 결과(FNC-A-017). mode는 이력이 없어 실제로 지웠으면 HARD, 이력이 있어 삭제 표시만 했으면 SOFT. */
// mode 값은 "HARD"(실제 삭제) 또는 "SOFT"(삭제 표시만).
public record ZoneDeleteResponse(String mode) {}
