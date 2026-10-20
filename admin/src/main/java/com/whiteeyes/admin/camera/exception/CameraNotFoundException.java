package com.whiteeyes.admin.camera.exception;

import com.whiteeyes.common.exception.BusinessException;
import com.whiteeyes.common.exception.ErrorCode;

// TODO(#11): 캠 등록(FNC-A-018)에서 ErrorCode에 CAMERA_NOT_FOUND가 생기면 그 코드로 바꾼다.
// 지금은 ErrorCode를 고치지 않으려고 공통 NOT_FOUND를 쓴다(#11·#105와 같은 줄 충돌 방지)
public class CameraNotFoundException extends BusinessException {

    public CameraNotFoundException(Long cameraId) {
        super(ErrorCode.NOT_FOUND, "캠을 찾을 수 없습니다. cameraId=" + cameraId);
    }
}
