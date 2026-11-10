package com.whiteeyes.common.region.exception;

import com.whiteeyes.common.exception.BusinessException;
import com.whiteeyes.common.exception.ErrorCode;

/** 없는 지역을 가리켰을 때(404 REGION_NOT_FOUND, FNC-C-008). */
public class RegionNotFoundException extends BusinessException {

    public RegionNotFoundException() {
        super(ErrorCode.REGION_NOT_FOUND);
    }
}
