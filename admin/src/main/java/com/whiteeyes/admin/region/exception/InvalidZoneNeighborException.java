package com.whiteeyes.admin.region.exception;

import com.whiteeyes.common.exception.BusinessException;
import com.whiteeyes.common.exception.ErrorCode;

/** 이웃 저장 요청이 잘못됐을 때(자기 자신을 이웃으로 지정, 알 수 없는 방향). 400 INVALID_INPUT. */
public class InvalidZoneNeighborException extends BusinessException {

    public InvalidZoneNeighborException(String message) {
        super(ErrorCode.INVALID_INPUT, message);
    }
}
