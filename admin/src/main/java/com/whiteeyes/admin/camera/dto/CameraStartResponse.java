package com.whiteeyes.admin.camera.dto;

import com.whiteeyes.common.camera.entity.CameraStatus;

/** 캠 가동 응답(POST /api/v1/manage/cameras/{id}/start). 분석 서버가 돌려준 상태(PREPARING 또는 RUNNING). */
public record CameraStartResponse(CameraStatus status) {}
