package com.whiteeyes.admin.camera.exception;

import com.whiteeyes.common.exception.BusinessException;
import com.whiteeyes.common.exception.ErrorCode;

/** 분석 서버에 연결하지 못했거나 응답이 비정상이다(502 ANALYZER_UNAVAILABLE). 원인 예외가 있으면 cause로 이어 둔다. */
public class AnalyzerUnavailableException extends BusinessException {

    // 응답은 받았지만 쓸 수 없는 경우(오류 응답, 알 수 없는 상태 값): 원인 예외가 없다.
    public AnalyzerUnavailableException() {
        super(ErrorCode.ANALYZER_UNAVAILABLE);
    }

    // 연결 실패·타임아웃처럼 하위 예외가 있는 경우: 로그에서 원인을 추적할 수 있게 cause를 보존한다.
    public AnalyzerUnavailableException(Throwable cause) {
        super(ErrorCode.ANALYZER_UNAVAILABLE, ErrorCode.ANALYZER_UNAVAILABLE.getMessage(), cause);
    }
}
