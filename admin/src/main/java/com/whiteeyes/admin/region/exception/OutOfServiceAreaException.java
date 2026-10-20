package com.whiteeyes.admin.region.exception;

import com.whiteeyes.common.exception.BusinessException;
import com.whiteeyes.common.exception.ErrorCode;

public class OutOfServiceAreaException extends BusinessException {

    public OutOfServiceAreaException(String message) {
        super(ErrorCode.OUT_OF_SERVICE_AREA, message);
    }
}
