package com.whiteeyes.admin.media.exception;

import com.whiteeyes.common.exception.BusinessException;
import com.whiteeyes.common.exception.ErrorCode;

/** 찾는 시각이 녹화 보관 기간(30일)을 지났을 때(404 RECORDING_EXPIRED, 화면 문구 '보관 기간 지남'). */
public class RecordingExpiredException extends BusinessException {

    // 오류 코드의 기본 메시지를 그대로 쓴다.
    public RecordingExpiredException() {
        super(ErrorCode.RECORDING_EXPIRED);
    }
}
