package com.whiteeyes.admin.media.exception;

import com.whiteeyes.common.exception.BusinessException;
import com.whiteeyes.common.exception.ErrorCode;

/** 구역·시각에 해당하는 녹화가 없을 때(404 RECORDING_NOT_FOUND, 화면 문구 '영상 없음'). */
public class RecordingNotFoundException extends BusinessException {

    public RecordingNotFoundException() {
        super(ErrorCode.RECORDING_NOT_FOUND);
    }
}
