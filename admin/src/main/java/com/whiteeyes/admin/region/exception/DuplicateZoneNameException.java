package com.whiteeyes.admin.region.exception;

import com.whiteeyes.common.exception.BusinessException;
import com.whiteeyes.common.exception.ErrorCode;

/** 같은 읍·면·동 안에 삭제되지 않은 같은 이름의 구역이 이미 있을 때(409 DUPLICATE_ZONE_NAME, FNC-A-014·015). */
public class DuplicateZoneNameException extends BusinessException {

    /** @param name 겹친 구역 이름 */
    public DuplicateZoneNameException(String name) {
        super(ErrorCode.DUPLICATE_ZONE_NAME, "같은 동에 '" + name + "' 구역이 이미 있습니다.");
    }
}
