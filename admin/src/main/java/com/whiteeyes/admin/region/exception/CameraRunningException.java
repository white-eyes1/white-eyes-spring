package com.whiteeyes.admin.region.exception;

import com.whiteeyes.common.exception.BusinessException;
import com.whiteeyes.common.exception.ErrorCode;

/** 준비 중이거나 가동 중인 캠이 있는 구역을 지우려 할 때(409 CAMERA_RUNNING, FNC-A-017). */
public class CameraRunningException extends BusinessException {

    public CameraRunningException(Long zoneId) {
        super(ErrorCode.CAMERA_RUNNING, "준비 중이거나 가동 중인 캠이 있어 구역을 삭제할 수 없습니다. zoneId=" + zoneId);
    }
}
