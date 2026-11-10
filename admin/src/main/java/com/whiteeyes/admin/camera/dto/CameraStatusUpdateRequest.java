package com.whiteeyes.admin.camera.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import com.whiteeyes.common.camera.entity.CameraStatus;

public record CameraStatusUpdateRequest(
        @NotNull CameraStatus status, @Min(0) @Max(100) Integer progress, @Size(max = 500) String errorMessage) {}
