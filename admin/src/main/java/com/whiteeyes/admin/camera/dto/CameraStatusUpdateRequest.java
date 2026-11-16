package com.whiteeyes.admin.camera.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import com.whiteeyes.common.camera.entity.CameraStatus;

/**
 * 분석 서버가 보내는 캠 상태 보고 본문(PATCH /internal/v1/cameras/{id}/status).
 * status는 필수, progress는 0~100(준비 중일 때만 의미), errorMessage는 오류 상태일 때의 사유(최대 500자).
 */
public record CameraStatusUpdateRequest(
        @NotNull CameraStatus status, @Min(0) @Max(100) Integer progress, @Size(max = 500) String errorMessage) {}
