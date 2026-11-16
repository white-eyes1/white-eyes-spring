package com.whiteeyes.admin.camera.exception;

import com.whiteeyes.common.exception.BusinessException;
import com.whiteeyes.common.exception.ErrorCode;

/** 분석 서버가 영상 파일·스트림을 찾지 못했다(SOURCE_NOT_FOUND). 영상 주소를 고쳐야 하는 문제다. */
public class SourceNotFoundException extends BusinessException {

    public SourceNotFoundException(Long cameraId) {
        super(ErrorCode.SOURCE_NOT_FOUND, "영상 파일·스트림을 찾을 수 없습니다. cameraId=" + cameraId);
    }
}
