package com.whiteeyes.admin.region.exception;

import com.whiteeyes.common.exception.BusinessException;
import com.whiteeyes.common.exception.ErrorCode;

/** 다각형 내부 점이 어떤 읍·면·동 경계에도 들어가지 않을 때, 즉 서비스 지역 밖일 때(OUT_OF_SERVICE_AREA, FNC-A-013). */
public class OutOfServiceAreaException extends BusinessException {

    public OutOfServiceAreaException(String message) {
        super(ErrorCode.OUT_OF_SERVICE_AREA, message);
    }
}
