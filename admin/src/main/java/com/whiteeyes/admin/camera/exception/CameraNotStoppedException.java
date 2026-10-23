package com.whiteeyes.admin.camera.exception;

import com.whiteeyes.common.camera.entity.CameraStatus;
import com.whiteeyes.common.exception.BusinessException;
import com.whiteeyes.common.exception.ErrorCode;

public class CameraNotStoppedException extends BusinessException {

    public CameraNotStoppedException(Long cameraId, CameraStatus status) {
        super(ErrorCode.CAMERA_NOT_STOPPED, "정지된 캠만 삭제할 수 있습니다. 현재 상태=" + status + ", cameraId=" + cameraId);
    }
}
