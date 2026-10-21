package com.whiteeyes.admin.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * /internal/** 은 내부 포트로만, 내부 포트로는 /internal/** 만 받는다(FNC-C-003, conventions.md '모듈·포트').
 * 두 포트가 같은 서블릿 컨텍스트를 공유하므로 어느 포트로 들어왔는지로 갈라낸다. 맞지 않으면 없는 경로처럼 404를 준다.
 * 보안 필터보다 앞에서 돌아 인증 여부와 상관없이 경로가 존재하는지도 드러내지 않는다.
 */
public class InternalPortFilter extends OncePerRequestFilter {

    private static final String INTERNAL_PATH = "/internal";

    private final int internalPort;

    public InternalPortFilter(int internalPort) {
        this.internalPort = internalPort;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        boolean onInternalPort = request.getLocalPort() == internalPort;
        if (onInternalPort == isInternalPath(request)) {
            filterChain.doFilter(request, response);
            return;
        }
        response.sendError(HttpServletResponse.SC_NOT_FOUND);
    }

    private boolean isInternalPath(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return path.equals(INTERNAL_PATH) || path.startsWith(INTERNAL_PATH + "/");
    }
}
