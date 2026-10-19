package com.whiteeyes.admin.camera.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

// TODO(FNC-C-027): 공통 오류 처리가 생기면 BusinessException·ErrorCode(CAMERA_NOT_FOUND)로 바꾼다
@ResponseStatus(HttpStatus.NOT_FOUND)
public class CameraNotFoundException extends RuntimeException {

    public CameraNotFoundException(Long cameraId) {
        super("캠을 찾을 수 없습니다. cameraId=" + cameraId);
    }
}
