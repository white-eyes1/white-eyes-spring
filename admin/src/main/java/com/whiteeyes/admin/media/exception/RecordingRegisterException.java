package com.whiteeyes.admin.media.exception;

import com.whiteeyes.common.exception.BusinessException;
import com.whiteeyes.common.exception.ErrorCode;

/**
 * 녹화 등록의 경로·endedAt 규칙 위반(FNC-A-026, 400). message에 어긴 규칙을 적는다.
 */
public class RecordingRegisterException extends BusinessException {

    public RecordingRegisterException(String message) {
        super(ErrorCode.INVALID_INPUT, message);
    }
}
