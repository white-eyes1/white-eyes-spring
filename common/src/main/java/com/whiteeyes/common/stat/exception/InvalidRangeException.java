package com.whiteeyes.common.stat.exception;

import com.whiteeyes.common.exception.BusinessException;
import com.whiteeyes.common.exception.ErrorCode;

/** 조회 기간이 뒤집혔거나 최대 기간을 넘었을 때(400 INVALID_RANGE, FNC-C-020 '기간 오류'). */
public class InvalidRangeException extends BusinessException {

    public InvalidRangeException(String message) {
        super(ErrorCode.INVALID_RANGE, message);
    }
}
