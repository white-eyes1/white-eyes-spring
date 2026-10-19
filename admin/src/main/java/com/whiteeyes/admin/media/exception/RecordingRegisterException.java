package com.whiteeyes.admin.media.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

// TODO(FNC-C-027): 공통 오류 처리가 생기면 BusinessException·ErrorCode로 바꾼다
@ResponseStatus(HttpStatus.BAD_REQUEST)
public class RecordingRegisterException extends RuntimeException {

    public RecordingRegisterException(String message) {
        super(message);
    }
}
