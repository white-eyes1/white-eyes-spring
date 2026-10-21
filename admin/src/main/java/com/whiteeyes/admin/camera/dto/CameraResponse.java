package com.whiteeyes.admin.camera.dto;

import com.whiteeyes.common.camera.entity.Camera;
import com.whiteeyes.common.camera.entity.CameraSourceType;
import com.whiteeyes.common.camera.entity.CameraStatus;

public record CameraResponse(
        Long id,
        Long zoneId,
        String name,
        CameraSourceType sourceType,
        String sourceUri,
        CameraStatus status,
        int progress,
        String errorMessage,
        boolean runRequested) {

    public static CameraResponse from(Camera camera) {
        return new CameraResponse(
                camera.getId(),
                camera.getZoneId(),
                camera.getName(),
                camera.getSourceType(),
                camera.getSourceUri(),
                camera.getStatus(),
                camera.getProgress(),
                camera.getErrorMessage(),
                camera.isRunRequested());
    }
}
