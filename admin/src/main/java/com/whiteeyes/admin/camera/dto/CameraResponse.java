package com.whiteeyes.admin.camera.dto;

import com.whiteeyes.common.camera.entity.Camera;
import com.whiteeyes.common.camera.entity.CameraSourceType;
import com.whiteeyes.common.camera.entity.CameraStatus;

/** 캠 한 건의 기본 응답(등록·수정 결과). 목록용 상세(통계 등)는 CameraListItem이 맡는다. */
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

    // 엔티티를 응답 DTO로 옮긴다. 엔티티를 그대로 내보내지 않아 화면에 필요한 값만 노출한다.
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
