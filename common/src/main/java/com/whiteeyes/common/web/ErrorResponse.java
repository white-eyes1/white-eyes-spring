package com.whiteeyes.common.web;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 공통 오류 응답 본문(conventions.md '공통 API 규칙 > 오류 응답').
 * fieldErrors는 입력 검증 오류일 때만 내보낸다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(String code, String message, List<FieldErrorResponse> fieldErrors) {

    public record FieldErrorResponse(String field, String message) {}

    /** 입력 검증 오류가 아닌 경우의 응답(fieldErrors 없음). */
    public static ErrorResponse of(String code, String message) {
        return new ErrorResponse(code, message, null);
    }
}
