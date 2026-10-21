package com.whiteeyes.admin.camera.exception;

import com.whiteeyes.common.exception.BusinessException;
import com.whiteeyes.common.exception.ErrorCode;

public class SourceNotFoundException extends BusinessException {

    public SourceNotFoundException(Long cameraId) {
        super(ErrorCode.SOURCE_NOT_FOUND, "영상 파일·스트림을 찾을 수 없습니다. cameraId=" + cameraId);
    }
}
