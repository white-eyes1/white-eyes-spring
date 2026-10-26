package com.whiteeyes.common.event.dto;

/** 미확인 이벤트 수(FNC-A-048). GET /api/v1/events/unchecked-count 응답이고 알림 SSE의 unchecked-count 데이터이기도 하다. */
public record UncheckedCountResponse(long count) {}
