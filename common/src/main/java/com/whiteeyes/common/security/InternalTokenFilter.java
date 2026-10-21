package com.whiteeyes.common.security;

import com.whiteeyes.common.exception.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

/**
 * 내부 API(/internal/**)는 세션 대신 X-Internal-Token 헤더로만 인증한다(FNC-C-003, conventions.md '내부 통신 인증').
 * 서버에 설정된 토큰이 비어 있으면 빈 헤더끼리 같아 보이지 않도록 모든 요청을 거부한다.
 */
public class InternalTokenFilter extends OncePerRequestFilter {

    public static final String TOKEN_HEADER = "X-Internal-Token";

    private static final Logger log = LoggerFactory.getLogger(InternalTokenFilter.class);

    private final byte[] expectedToken;
    private final JsonMapper jsonMapper;

    public InternalTokenFilter(String expectedToken, JsonMapper jsonMapper) {
        this.expectedToken =
                StringUtils.hasText(expectedToken) ? expectedToken.getBytes(StandardCharsets.UTF_8) : null;
        this.jsonMapper = jsonMapper;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (isValid(request.getHeader(TOKEN_HEADER))) {
            filterChain.doFilter(request, response);
            return;
        }
        // 토큰 값은 남기지 않는다(code-conventions.md 7-4)
        log.warn("내부 API 토큰 불일치 uri={}", request.getRequestURI());
        SecurityErrorWriter.write(response, jsonMapper, ErrorCode.UNAUTHORIZED);
    }

    private boolean isValid(String sentToken) {
        if (expectedToken == null || sentToken == null) {
            return false;
        }
        // 비교에 걸리는 시간으로 토큰을 짐작하지 못하게 상수 시간으로 비교한다
        return MessageDigest.isEqual(expectedToken, sentToken.getBytes(StandardCharsets.UTF_8));
    }
}
