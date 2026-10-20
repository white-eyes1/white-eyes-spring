package com.whiteeyes.admin.camera.exception;

import com.whiteeyes.common.exception.BusinessException;
import com.whiteeyes.common.exception.ErrorCode;

public class ZoneHasCameraException extends BusinessException {

    public ZoneHasCameraException(Long zoneId) {
        super(ErrorCode.ZONE_HAS_CAMERA, "이 구역에는 이미 캠이 있습니다. zoneId=" + zoneId);
    }
}
