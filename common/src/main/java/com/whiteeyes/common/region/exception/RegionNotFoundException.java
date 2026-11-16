package com.whiteeyes.common.region.exception;

import com.whiteeyes.common.exception.BusinessException;
import com.whiteeyes.common.exception.ErrorCode;

/** 없는 지역을 가리켰을 때(404 REGION_NOT_FOUND, FNC-C-008). */
public class RegionNotFoundException extends BusinessException {

    public RegionNotFoundException() {
        // 기본 메시지는 ErrorCode에 정의돼 있다
        super(ErrorCode.REGION_NOT_FOUND);
    }
}
