package com.whiteeyes.common.security;

import com.whiteeyes.common.exception.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.DefaultRedirectStrategy;
import org.springframework.security.web.RedirectStrategy;
import tools.jackson.databind.json.JsonMapper;

/**
 * 세션이 없는 요청의 처리(FNC-C-003 처리 순서 2): API는 401 공통 오류 응답, 화면은 로그인 화면으로 보낸다.
 */
public class LoginRequiredEntryPoint implements AuthenticationEntryPoint {

    private static final String LOGIN_URL = "/login";

    private final JsonMapper jsonMapper;
    private final RedirectStrategy redirectStrategy = new DefaultRedirectStrategy();

    public LoginRequiredEntryPoint(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    @Override
    public void commence(
            HttpServletRequest request, HttpServletResponse response, AuthenticationException authException)
            throws IOException {
        if (ApiRequests.isApi(request)) {
            SecurityErrorWriter.write(response, jsonMapper, ErrorCode.UNAUTHORIZED);
            return;
        }
        // 컨텍스트 경로(관리자 앱은 /admin)는 redirect 전략이 붙인다
        redirectStrategy.sendRedirect(request, response, LOGIN_URL);
    }
}
