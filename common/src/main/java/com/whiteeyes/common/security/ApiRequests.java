package com.whiteeyes.common.security;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;

/**
 * 요청이 API인지 화면인지 경로로 판단한다(conventions.md '인증 오류'). 컨텍스트 경로(관리자 앱은 /admin)는 뗀 경로로 본다.
 * GlobalExceptionHandler도 같은 기준이다.
 */
final class ApiRequests {

    private static final List<String> API_PATH_PREFIXES = List.of("/api/", "/internal/");

    private ApiRequests() {}

    static String pathWithinApplication(HttpServletRequest request) {
        return request.getRequestURI().substring(request.getContextPath().length());
    }

    static boolean isApi(HttpServletRequest request) {
        String path = pathWithinApplication(request);
        return API_PATH_PREFIXES.stream().anyMatch(path::startsWith);
    }
}
