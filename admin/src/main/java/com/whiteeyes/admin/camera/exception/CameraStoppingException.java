package com.whiteeyes.admin.camera.exception;

import com.whiteeyes.common.exception.BusinessException;
import com.whiteeyes.common.exception.ErrorCode;

/** 분석 서버가 직전 정지가 끝나지 않았다며(409 CAMERA_STOPPING) 가동을 거절했다. */
public class CameraStoppingException extends BusinessException {

    // 응답 문구가 화면에 그대로 나가므로(screens.md) 캠 번호를 붙이지 않고 기본 메시지를 쓴다
    public CameraStoppingException() {
        super(ErrorCode.CAMERA_STOPPING);
    }
}
