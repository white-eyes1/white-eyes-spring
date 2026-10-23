package com.whiteeyes.admin.camera.exception;

import com.whiteeyes.common.camera.entity.CameraStatus;
import com.whiteeyes.common.exception.BusinessException;
import com.whiteeyes.common.exception.ErrorCode;

public class CameraNotStoppedException extends BusinessException {

    public CameraNotStoppedException(Long cameraId, CameraStatus status, boolean runRequested) {
        super(
                ErrorCode.CAMERA_NOT_STOPPED,
                "정지 상태에서만 처리할 수 있습니다. 먼저 캠을 정지해 주세요. 현재 상태=" + status + ", 가동 요청=" + runRequested
                        + ", cameraId=" + cameraId);
    }
}
