package com.whiteeyes.common.exception;

/**
 * 예상된 업무 오류. 던지면 전역 예외 처리가 ErrorCode의 상태와 code로 응답한다(FNC-C-027).
 * 기능별 예외는 이 클래스를 상속한다(예: ZoneNotFoundException).
 */
public class BusinessException extends RuntimeException {

    // 어떤 종류의 오류인지. 전역 예외 처리기가 여기서 HTTP 상태와 code 값을 꺼낸다
    private final ErrorCode errorCode;

    /** 기본 메시지(ErrorCode의 문구)를 그대로 쓰는 생성자. */
    public BusinessException(ErrorCode errorCode) {
        // 메시지를 따로 주지 않았으면 오류 코드의 기본 문구를 쓴다
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

    /** 처리기가 응답 상태와 code를 정할 때 읽는다. */
    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
