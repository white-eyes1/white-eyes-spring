package com.whiteeyes.admin.region.exception;

import com.whiteeyes.common.exception.BusinessException;
import com.whiteeyes.common.exception.ErrorCode;

/** 이웃 저장 요청이 잘못됐을 때(자기 자신을 이웃으로 지정, 알 수 없는 방향). 400 INVALID_INPUT. */
public class InvalidZoneNeighborException extends BusinessException {

    /** @param message 어떤 지정이 잘못됐는지(자기 자신, 방향 이름 오류 등) 사용자에게 보여 줄 사유 */
    public InvalidZoneNeighborException(String message) {
        super(ErrorCode.INVALID_INPUT, message);
    }
}
