package com.whiteeyes.admin.region.exception;

import com.whiteeyes.common.exception.BusinessException;
import com.whiteeyes.common.exception.ErrorCode;

/** 요청 다각형이 GeoJSON Polygon이 아니거나 좌표·도형이 올바르지 않을 때(INVALID_POLYGON, FNC-A-013). */
public class InvalidPolygonException extends BusinessException {

    /** 원인 예외 없이 사유 메시지만 알릴 때. */
    public InvalidPolygonException(String message) {
        super(ErrorCode.INVALID_POLYGON, message);
    }

    /** JTS가 던진 IllegalArgumentException 같은 원인 예외를 함께 보존할 때. */
    public InvalidPolygonException(String message, Throwable cause) {
        super(ErrorCode.INVALID_POLYGON, message, cause);
    }
}
