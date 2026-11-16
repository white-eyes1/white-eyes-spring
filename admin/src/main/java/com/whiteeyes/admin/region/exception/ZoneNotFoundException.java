package com.whiteeyes.admin.region.exception;

import com.whiteeyes.common.exception.BusinessException;
import com.whiteeyes.common.exception.ErrorCode;

/** 구역이 없거나 이미 삭제됐을 때(404 ZONE_NOT_FOUND). 이웃으로 지정한 구역이 없을 때도 쓴다. */
public class ZoneNotFoundException extends BusinessException {

    public ZoneNotFoundException(Long zoneId) {
        super(ErrorCode.ZONE_NOT_FOUND, "구역을 찾을 수 없습니다. zoneId=" + zoneId);
    }
}
