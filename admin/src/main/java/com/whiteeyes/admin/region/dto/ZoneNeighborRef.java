package com.whiteeyes.admin.region.dto;

/** 한 방향의 이웃 구역(GET·PUT /api/v1/manage/zones/{id}/neighbors 응답). */
// 이웃이 없는 방향은 이 객체 대신 null로 응답에 들어간다
public record ZoneNeighborRef(Long id, String name) {}
