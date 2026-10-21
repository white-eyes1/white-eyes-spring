package com.whiteeyes.admin.config;

import com.whiteeyes.common.exception.ErrorCode;
import com.whiteeyes.common.security.SecurityErrorWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;
import tools.jackson.databind.json.JsonMapper;

/**
 * /internal/** 은 내부 포트로만, 내부 포트로는 /internal/** 만 받는다(FNC-C-003, conventions.md '모듈·포트').
 * 두 포트가 같은 서블릿 컨텍스트를 공유하므로 어느 포트로 들어왔는지로 갈라낸다. 맞지 않으면 없는 경로처럼 404를 준다.
 * 보안 필터보다 앞에서 돌아 인증 여부와 상관없이 경로가 존재하는지도 드러내지 않는다.
 */
public class InternalPortFilter extends OncePerRequestFilter {

    private static final String INTERNAL_PATH = "/internal";

    // 보안 필터가 보는 경로와 같게 %인코딩을 풀어서 판단한다
    private final UrlPathHelper pathHelper = new UrlPathHelper();
    private final int internalPort;
    private final JsonMapper jsonMapper;

    public InternalPortFilter(int internalPort, JsonMapper jsonMapper) {
        this.internalPort = internalPort;
        this.jsonMapper = jsonMapper;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        boolean onInternalPort = request.getLocalPort() == internalPort;
        boolean internalPath = isInternalPath(request);
        if (onInternalPort == internalPath) {
            filterChain.doFilter(request, response);
            return;
        }
        if (internalPath) {
            // 내부 API를 부르는 쪽은 JSON 오류 응답을 기대한다(conventions.md '인증 오류')
            SecurityErrorWriter.write(response, jsonMapper, ErrorCode.NOT_FOUND);
            return;
        }
        response.sendError(HttpServletResponse.SC_NOT_FOUND);
    }

    private boolean isInternalPath(HttpServletRequest request) {
        String path = pathHelper.getPathWithinApplication(request);
        return path.equals(INTERNAL_PATH) || path.startsWith(INTERNAL_PATH + "/");
    }
}
