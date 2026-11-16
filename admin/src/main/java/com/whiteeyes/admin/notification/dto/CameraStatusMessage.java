package com.whiteeyes.admin.notification.dto;

import com.whiteeyes.common.camera.entity.CameraStatus;

/** 알림 SSE의 camera-status 데이터(FNC-A-048, FNC-A-025 처리 순서 2). */
// 상태 보고(FNC-A-025)가 반영될 때 알림 SSE로 내려가는 값이다. progress·errorMessage는 해당 상태가 아니면 null일 수 있다.
public record CameraStatusMessage(Long cameraId, CameraStatus status, Integer progress, String errorMessage) {}
