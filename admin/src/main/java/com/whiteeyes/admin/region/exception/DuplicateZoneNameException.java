package com.whiteeyes.admin.region.exception;

import com.whiteeyes.common.exception.BusinessException;
import com.whiteeyes.common.exception.ErrorCode;

public class DuplicateZoneNameException extends BusinessException {

    public DuplicateZoneNameException(String name) {
        super(ErrorCode.DUPLICATE_ZONE_NAME, "같은 동에 '" + name + "' 구역이 이미 있습니다.");
    }
}
