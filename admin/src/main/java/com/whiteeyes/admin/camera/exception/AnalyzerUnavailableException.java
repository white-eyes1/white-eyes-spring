package com.whiteeyes.admin.camera.exception;

import com.whiteeyes.common.exception.BusinessException;
import com.whiteeyes.common.exception.ErrorCode;

public class AnalyzerUnavailableException extends BusinessException {

    public AnalyzerUnavailableException() {
        super(ErrorCode.ANALYZER_UNAVAILABLE);
    }

    public AnalyzerUnavailableException(Throwable cause) {
        super(ErrorCode.ANALYZER_UNAVAILABLE, ErrorCode.ANALYZER_UNAVAILABLE.getMessage(), cause);
    }
}
