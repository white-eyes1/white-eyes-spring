package com.whiteeyes.admin.camera.dto;

import com.whiteeyes.common.camera.entity.CameraStatus;

/** 캠 가동 응답(POST /api/v1/manage/cameras/{id}/start). 분석 서버가 돌려준 상태(PREPARING 또는 RUNNING). */
// 분석 서버가 시작 직후 알려 준 상태(PREPARING 또는 RUNNING). 이후 상태 변화는 분석 서버의 상태 보고(FNC-A-025)로 갱신된다.
public record CameraStartResponse(CameraStatus status) {}
