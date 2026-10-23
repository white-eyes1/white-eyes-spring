package com.whiteeyes.admin.camera.exception;

import com.whiteeyes.common.camera.entity.CameraStatus;
import com.whiteeyes.common.exception.BusinessException;
import com.whiteeyes.common.exception.ErrorCode;

/** 정지 상태가 아닌 캠의 정보를 고치려 할 때(409 CAMERA_NOT_STOPPED, FNC-A-019). */
public class CameraNotStoppedException extends BusinessException {

    public CameraNotStoppedException(Long cameraId, CameraStatus status) {
        super(ErrorCode.CAMERA_NOT_STOPPED, "정지 상태에서만 캠 정보를 고칠 수 있습니다. 현재 상태=" + status + ", cameraId=" + cameraId);
    }
}
