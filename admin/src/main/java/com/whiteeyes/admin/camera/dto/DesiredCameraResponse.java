package com.whiteeyes.admin.camera.dto;

import com.whiteeyes.common.camera.entity.Camera;
import com.whiteeyes.common.camera.entity.CameraSourceType;

/** 가동 대상 캠 한 건(GET /internal/v1/cameras/desired). 분석 서버가 이 목록대로 다시 가동한다. */
public record DesiredCameraResponse(Long cameraId, CameraSourceType sourceType, String sourceUri) {

    // 분석 서버가 캠을 다시 띄우는 데 필요한 값(ID·입력 방식·영상 주소)만 담는다.
    public static DesiredCameraResponse from(Camera camera) {
        return new DesiredCameraResponse(camera.getId(), camera.getSourceType(), camera.getSourceUri());
    }
}
