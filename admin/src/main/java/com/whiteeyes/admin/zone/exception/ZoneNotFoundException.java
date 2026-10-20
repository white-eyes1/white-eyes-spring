package com.whiteeyes.admin.zone.exception;

import com.whiteeyes.common.exception.BusinessException;
import com.whiteeyes.common.exception.ErrorCode;

public class ZoneNotFoundException extends BusinessException {

    public ZoneNotFoundException(Long zoneId) {
        super(ErrorCode.ZONE_NOT_FOUND, "구역을 찾을 수 없습니다. zoneId=" + zoneId);
    }
}
