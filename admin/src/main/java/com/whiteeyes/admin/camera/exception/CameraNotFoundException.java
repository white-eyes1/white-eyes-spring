package com.whiteeyes.admin.camera.exception;

import com.whiteeyes.common.exception.BusinessException;
import com.whiteeyes.common.exception.ErrorCode;

// 명세(FNC-A-020·021)가 캠 없음을 공통 NOT_FOUND로 정하고 CAMERA_NOT_FOUND는 따로 두지 않아 공통 코드를 쓴다
public class CameraNotFoundException extends BusinessException {

    public CameraNotFoundException(Long cameraId) {
        super(ErrorCode.NOT_FOUND, "캠을 찾을 수 없습니다. cameraId=" + cameraId);
    }
}
