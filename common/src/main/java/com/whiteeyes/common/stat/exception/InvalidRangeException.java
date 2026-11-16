package com.whiteeyes.common.stat.exception;

import com.whiteeyes.common.exception.BusinessException;
import com.whiteeyes.common.exception.ErrorCode;

/** 조회 기간이 뒤집혔거나 최대 기간을 넘었을 때(400 INVALID_RANGE, FNC-C-020 '기간 오류'). */
public class InvalidRangeException extends BusinessException {

    // 상황마다 문구가 달라(순서 뒤바뀜, 1년 초과 등) 메시지는 호출하는 쪽에서 받고, 오류 코드만 INVALID_RANGE로 고정한다
    public InvalidRangeException(String message) {
        super(ErrorCode.INVALID_RANGE, message);
    }
}
