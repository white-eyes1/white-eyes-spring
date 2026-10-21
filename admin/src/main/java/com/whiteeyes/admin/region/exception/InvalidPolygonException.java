package com.whiteeyes.admin.region.exception;

import com.whiteeyes.common.exception.BusinessException;
import com.whiteeyes.common.exception.ErrorCode;

public class InvalidPolygonException extends BusinessException {

    public InvalidPolygonException(String message) {
        super(ErrorCode.INVALID_POLYGON, message);
    }

    public InvalidPolygonException(String message, Throwable cause) {
        super(ErrorCode.INVALID_POLYGON, message, cause);
    }
}
