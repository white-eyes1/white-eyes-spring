package com.whiteeyes.admin.camera.exception;

import com.whiteeyes.common.camera.entity.CameraStatus;
import com.whiteeyes.common.exception.BusinessException;
import com.whiteeyes.common.exception.ErrorCode;

public class InvalidCameraStatusException extends BusinessException {

    public InvalidCameraStatusException(Long cameraId, CameraStatus status) {
        super(ErrorCode.INVALID_STATUS, "현재 상태(" + status + ")에서는 가동할 수 없습니다. cameraId=" + cameraId);
    }
}
