package com.whiteeyes.admin.camera.exception;

import com.whiteeyes.common.exception.BusinessException;
import com.whiteeyes.common.exception.ErrorCode;

public class CameraNotFoundException extends BusinessException {

    public CameraNotFoundException(Long cameraId) {
        super(ErrorCode.CAMERA_NOT_FOUND, "캠을 찾을 수 없습니다. cameraId=" + cameraId);
    }
}
