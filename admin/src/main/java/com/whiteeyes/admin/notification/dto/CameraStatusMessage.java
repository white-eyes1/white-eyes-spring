package com.whiteeyes.admin.notification.dto;

import com.whiteeyes.common.camera.entity.CameraStatus;

/** 알림 SSE의 camera-status 데이터(FNC-A-048, FNC-A-025 처리 순서 2). */
public record CameraStatusMessage(Long cameraId, CameraStatus status, Integer progress, String errorMessage) {}
