package com.whiteeyes.common.exception;

import org.springframework.http.HttpStatus;

/**
 * 오류 응답의 code 값과 HTTP 상태, 기본 메시지(FNC-C-027, conventions.md '오류 응답').
 * 기능별 코드(예: ZONE_NOT_FOUND)는 그 기능의 이슈에서 이 enum에 추가한다.
 */
public enum ErrorCode {
    INVALID_INPUT(HttpStatus.BAD_REQUEST, "입력값을 확인해 주세요."),
    INVALID_POLYGON(HttpStatus.BAD_REQUEST, "구역 모양이 올바르지 않습니다."),
    OUT_OF_SERVICE_AREA(HttpStatus.BAD_REQUEST, "서비스 지역 밖입니다."),
    RANGE_TOO_LONG(HttpStatus.BAD_REQUEST, "조회 기간이 너무 깁니다."),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다."),
    FORBIDDEN(HttpStatus.FORBIDDEN, "권한이 없습니다."),
    NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 대상을 찾을 수 없습니다."),
    ZONE_NOT_FOUND(HttpStatus.NOT_FOUND, "구역을 찾을 수 없습니다."),
    REGION_NOT_FOUND(HttpStatus.NOT_FOUND, "지역을 찾을 수 없습니다."),
    RECORDING_NOT_FOUND(HttpStatus.NOT_FOUND, "영상 없음"),
    RECORDING_EXPIRED(HttpStatus.NOT_FOUND, "보관 기간 지남"),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "지원하지 않는 요청 방식입니다."),
    DUPLICATE(HttpStatus.CONFLICT, "이미 존재합니다."),
    DUPLICATE_LOGIN_ID(HttpStatus.CONFLICT, "이미 사용 중인 아이디입니다."),
    DUPLICATE_ZONE_NAME(HttpStatus.CONFLICT, "같은 동에 같은 이름의 구역이 있습니다."),
    ZONE_HAS_CAMERA(HttpStatus.CONFLICT, "이 구역에는 이미 캠이 있습니다."),
    STATUS_CONFLICT(HttpStatus.CONFLICT, "현재 상태에서는 처리할 수 없습니다."),
    INVALID_STATUS(HttpStatus.CONFLICT, "현재 캠 상태에서는 처리할 수 없습니다."),
    CAMERA_STOPPING(HttpStatus.CONFLICT, "이전 정지가 아직 끝나지 않았습니다. 잠시 뒤 다시 시작해 주세요."),
    SOURCE_NOT_FOUND(HttpStatus.UNPROCESSABLE_ENTITY, "영상 파일·스트림을 찾을 수 없습니다."),
    ANALYZER_UNAVAILABLE(HttpStatus.BAD_GATEWAY, "분석 서버에 연결할 수 없습니다."),
    EXTERNAL_UNAVAILABLE(HttpStatus.BAD_GATEWAY, "외부 서버에 연결할 수 없습니다."),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "일시적인 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.");

    private final HttpStatus status;
    private final String message;

    ErrorCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getMessage() {
        return message;
    }
}
