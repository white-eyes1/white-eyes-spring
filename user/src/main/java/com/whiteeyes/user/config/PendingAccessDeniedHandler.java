package com.whiteeyes.user.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.DefaultRedirectStrategy;
import org.springframework.security.web.RedirectStrategy;
import org.springframework.security.web.access.AccessDeniedHandler;
import tools.jackson.databind.json.JsonMapper;

import com.whiteeyes.common.exception.ErrorCode;
import com.whiteeyes.common.web.ErrorResponse;

/**
 * 권한이 모자란 요청을 처리한다(FNC-U-003 처리 순서 2).
 * API는 공통 오류 응답으로 403, 화면은 승인 대기 계정이면 승인 대기 화면으로 보내고 그 밖에는 403 오류 화면을 보인다.
 * API인지는 GlobalExceptionHandler와 같이 경로로 판단한다(conventions.md '인증 오류').
 */
public class PendingAccessDeniedHandler implements AccessDeniedHandler {

    private static final List<String> API_PATH_PREFIXES = List.of("/api/", "/internal/");

    private final JsonMapper jsonMapper;
    private final RedirectStrategy redirectStrategy = new DefaultRedirectStrategy();

    public PendingAccessDeniedHandler(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException ex)
            throws IOException {
        if (isApiRequest(request)) {
            writeForbidden(response);
            return;
        }
        if (isPending(SecurityContextHolder.getContext().getAuthentication())) {
            redirectStrategy.sendRedirect(request, response, UserSecurityConfig.PENDING_PATH);
            return;
        }
        response.sendError(HttpServletResponse.SC_FORBIDDEN);
    }

    private boolean isApiRequest(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return API_PATH_PREFIXES.stream().anyMatch(path::startsWith);
    }

    private boolean isPending(Authentication authentication) {
        return authentication != null
                && authentication.getAuthorities().stream()
                        .anyMatch(authority -> UserRoles.PENDING_AUTHORITY.equals(authority.getAuthority()));
    }

    private void writeForbidden(HttpServletResponse response) throws IOException {
        ErrorCode errorCode = ErrorCode.FORBIDDEN;
        response.setStatus(errorCode.getStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        jsonMapper.writeValue(response.getWriter(), ErrorResponse.of(errorCode.name(), errorCode.getMessage()));
    }
}
