/*
 * [해석본] 원본: common/src/main/java/com/whiteeyes/common/exception/ErrorCode.java (feature/30-signup 브랜치)
 * 관련 이슈: #30(FNC-U-002). 파일은 #96(FNC-C-027 공통 오류 처리)이 만들었다
 *
 * [무엇] 오류 응답의 code·HTTP 상태·기본 문구. #30은 DUPLICATE_LOGIN_ID 한 줄만 더했다.
 */
package com.whiteeyes.common.exception;

import org.springframework.http.HttpStatus;

/**
 * 오류 응답의 code 값과 HTTP 상태, 기본 메시지(FNC-C-027, conventions.md '오류 응답').
 * 기능별 코드(예: ZONE_NOT_FOUND)는 그 기능의 이슈에서 이 enum에 추가한다.
 */
public enum ErrorCode {
    INVALID_INPUT(HttpStatus.BAD_REQUEST, "입력값을 확인해 주세요."),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다."),
    FORBIDDEN(HttpStatus.FORBIDDEN, "권한이 없습니다."),
    NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 대상을 찾을 수 없습니다."),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "지원하지 않는 요청 방식입니다."),
    DUPLICATE(HttpStatus.CONFLICT, "이미 존재합니다."),
    // [해석] public.md 오류 응답 '409 DUPLICATE_LOGIN_ID'. 이 enum의 주석대로 기능별 코드는 그 기능의 이슈에서 추가한다.
    //        일반 DUPLICATE('이미 존재합니다.')가 있는데도 따로 두는 이유: 화면이 code를 보고 '아이디 칸 오류'를 고른다
    //        (SCR-U-001 '409면 아이디 칸 오류'). 문구는 명세에 없어 정했다(PR '검토 필요').
    DUPLICATE_LOGIN_ID(HttpStatus.CONFLICT, "이미 사용 중인 아이디입니다."),
    STATUS_CONFLICT(HttpStatus.CONFLICT, "현재 상태에서는 처리할 수 없습니다."),
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
