package com.whiteeyes.admin.media.exception;

import com.whiteeyes.common.exception.BusinessException;
import com.whiteeyes.common.exception.ErrorCode;

/** 이어서 틀 다음 녹화가 없을 때(404, 화면 문구 '이후 영상 없음', FNC-A-045). */
public class NextRecordingNotFoundException extends BusinessException {

    public NextRecordingNotFoundException() {
        super(ErrorCode.RECORDING_NOT_FOUND, "이후 영상 없음");
    }
}
