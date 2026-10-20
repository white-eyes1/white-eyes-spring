package com.whiteeyes.common.exception;

/**
 * 예상된 업무 오류. 던지면 전역 예외 처리가 ErrorCode의 상태와 code로 응답한다(FNC-C-027).
 * 기능별 예외는 이 클래스를 상속한다(예: ZoneNotFoundException).
 */
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode) {
        this(errorCode, errorCode.getMessage());
    }

    /** message는 화면에 그대로 보이므로 사용자가 읽을 한국어 문장으로 쓴다. */
    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    /** 다른 예외를 감쌀 때 원인 스택을 잃지 않도록 cause를 함께 받는다. */
    public BusinessException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
