package com.whiteeyes.common.security;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.web.util.UrlPathHelper;

/**
 * 요청이 API인지 화면인지 경로로 판단한다(conventions.md '인증 오류'). 컨텍스트 경로(관리자 앱은 /admin)는 뗀 경로로 본다.
 * GlobalExceptionHandler도 같은 기준이다. 사용자 앱의 PendingAccessDeniedHandler도 이 기준으로 화면·API를 가른다.
 */
public final class ApiRequests {

    private static final List<String> API_PATH_PREFIXES = List.of("/api/", "/internal/");
    private static final UrlPathHelper PATH_HELPER = new UrlPathHelper();

    private ApiRequests() {}

    // 보안 필터가 보는 경로와 같게 %인코딩을 풀고 ;뒤 경로 매개변수를 뗀 경로로 판단한다
    static String pathWithinApplication(HttpServletRequest request) {
        return PATH_HELPER.getPathWithinApplication(request);
    }

    public static boolean isApi(HttpServletRequest request) {
        String path = pathWithinApplication(request);
        return API_PATH_PREFIXES.stream().anyMatch(path::startsWith);
    }
}
