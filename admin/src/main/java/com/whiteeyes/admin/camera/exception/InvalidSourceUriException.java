package com.whiteeyes.admin.camera.exception;

import com.whiteeyes.common.exception.BusinessException;
import com.whiteeyes.common.exception.ErrorCode;

/** 영상 주소가 입력 방식의 규칙을 어겼다(400 INVALID_INPUT). message는 화면에 그대로 보인다. */
public class InvalidSourceUriException extends BusinessException {

    public InvalidSourceUriException(String message) {
        super(ErrorCode.INVALID_INPUT, message);
    }
}
