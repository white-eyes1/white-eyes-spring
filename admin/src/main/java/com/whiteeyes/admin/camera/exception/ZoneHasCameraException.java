package com.whiteeyes.admin.camera.exception;

import com.whiteeyes.common.exception.BusinessException;
import com.whiteeyes.common.exception.ErrorCode;

/** 구역당 캠은 하나라서, 이미 캠이 있는 구역에 또 등록하려 할 때 던진다(ZONE_HAS_CAMERA). */
public class ZoneHasCameraException extends BusinessException {

    public ZoneHasCameraException(Long zoneId) {
        super(ErrorCode.ZONE_HAS_CAMERA, "이 구역에는 이미 캠이 있습니다. zoneId=" + zoneId);
    }
}
