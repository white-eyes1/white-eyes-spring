package com.whiteeyes.common.security;

import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.util.UrlPathHelper;

/**
 * 요청이 API인지 화면인지 경로로 판단한다(conventions.md '인증 오류'). 컨텍스트 경로(관리자 앱은 /admin)는 뗀 경로로 본다.
 * GlobalExceptionHandler·ErrorPageController도 같은 기준이다. 사용자 앱의 PendingAccessDeniedHandler도 이 기준으로 화면·API를 가른다.
 */
public final class ApiRequests {

    // 이 경로로 시작하면 API 요청이고 나머지는 화면 요청이다
    private static final List<String> API_PATH_PREFIXES = List.of("/api/", "/internal/");
    // 스프링이 요청 경로를 해석하는 방식과 같게 경로를 구하는 도구
    private static final UrlPathHelper PATH_HELPER = new UrlPathHelper();

    // 정적 메서드만 모은 유틸 클래스라 객체를 만들지 못하게 막는다
    private ApiRequests() {}

    // 보안 필터가 보는 경로와 같게 %인코딩을 풀고 ;뒤 경로 매개변수를 뗀 경로로 판단한다
    static String pathWithinApplication(HttpServletRequest request) {
        return PATH_HELPER.getPathWithinApplication(request);
    }

    /** 요청 자체로 API인지 판단한다. */
    public static boolean isApi(HttpServletRequest request) {
        return isApi(pathWithinApplication(request));
    }

    // 오류 처리(/error)처럼 원래 요청 경로를 따로 꺼내 볼 때 쓴다. 경로는 컨텍스트 경로를 뗀 것이다
    /** 컨텍스트 경로를 뗀 경로 문자열로 API인지 판단한다. */
    public static boolean isApi(String pathWithinApplication) {
        return API_PATH_PREFIXES.stream().anyMatch(pathWithinApplication::startsWith);
    }
}
