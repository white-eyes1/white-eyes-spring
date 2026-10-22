package com.whiteeyes.admin.region.dto;

/** 한 방향의 이웃 구역(GET·PUT /api/v1/manage/zones/{id}/neighbors 응답). */
public record ZoneNeighborRef(Long id, String name) {}
