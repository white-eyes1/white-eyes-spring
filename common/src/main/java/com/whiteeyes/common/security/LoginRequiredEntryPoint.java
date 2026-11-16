package com.whiteeyes.common.security;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.DefaultRedirectStrategy;
import org.springframework.security.web.RedirectStrategy;
import tools.jackson.databind.json.JsonMapper;

import com.whiteeyes.common.exception.ErrorCode;

/**
 * 세션이 없는 요청의 처리(FNC-C-003 처리 순서 2): API는 401 공통 오류 응답, 화면은 로그인 화면으로 보낸다.
 */
public class LoginRequiredEntryPoint implements AuthenticationEntryPoint {

    // 화면 요청이 로그인 안 된 상태일 때 보낼 곳
    private static final String LOGIN_URL = "/login";

    // API 오류 응답을 JSON으로 쓰는 도구
    private final JsonMapper jsonMapper;
    // 컨텍스트 경로를 붙여 이동시키는 도구
    private final RedirectStrategy redirectStrategy = new DefaultRedirectStrategy();

    public LoginRequiredEntryPoint(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    @Override
    public void commence(
            HttpServletRequest request, HttpServletResponse response, AuthenticationException authException)
            throws IOException {
        // API는 화면으로 보낼 수 없으므로 공통 오류 형식의 JSON(401)을 쓴다
        if (ApiRequests.isApi(request)) {
            SecurityErrorWriter.write(response, jsonMapper, ErrorCode.UNAUTHORIZED);
            return;
        }
        // 컨텍스트 경로(관리자 앱은 /admin)는 redirect 전략이 붙인다
        redirectStrategy.sendRedirect(request, response, LOGIN_URL);
    }
}
