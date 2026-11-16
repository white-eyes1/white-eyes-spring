package com.whiteeyes.common.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

import com.whiteeyes.common.exception.ErrorCode;

/**
 * 내부 API(/internal/**)는 세션 대신 X-Internal-Token 헤더로만 인증한다(FNC-C-003, conventions.md '내부 통신 인증').
 * 서버에 설정된 토큰이 비어 있으면 빈 헤더끼리 같아 보이지 않도록 모든 요청을 거부한다.
 */
@Slf4j
public class InternalTokenFilter extends OncePerRequestFilter {

    // 분석 서버가 요청마다 이 헤더에 토큰을 실어 보낸다
    public static final String TOKEN_HEADER = "X-Internal-Token";

    // 비교하기 쉽게 바이트로 바꿔 둔 기대 토큰. 설정이 비어 있으면 null이고 그러면 모든 요청을 거부한다
    private final byte[] expectedToken;
    private final JsonMapper jsonMapper;

    public InternalTokenFilter(String expectedToken, JsonMapper jsonMapper) {
        // 토큰 설정이 없으면 null로 두어 isValid가 항상 거부하게 한다(빈 토큰끼리 일치해 통과하는 사고를 막는다)
        this.expectedToken = StringUtils.hasText(expectedToken) ? expectedToken.getBytes(StandardCharsets.UTF_8) : null;
        this.jsonMapper = jsonMapper;
    }

    /**
     * 요청 헤더의 토큰이 맞으면 다음 단계로 넘기고, 틀리거나 없으면 401 오류 응답을 쓰고 멈춘다.
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        // 토큰이 맞으면 요청을 그대로 다음 필터·컨트롤러로 넘긴다
        if (isValid(request.getHeader(TOKEN_HEADER))) {
            filterChain.doFilter(request, response);
            return;
        }
        // 토큰 값은 남기지 않는다(code-conventions.md 7-4)
        log.warn("내부 API 토큰 불일치 uri={}", request.getRequestURI());
        SecurityErrorWriter.write(response, jsonMapper, ErrorCode.UNAUTHORIZED);
    }

    /** 보낸 토큰이 기대 토큰과 같은지 본다. */
    private boolean isValid(String sentToken) {
        // 서버 토큰이 설정되지 않았거나 헤더가 없으면 비교할 것도 없이 거부한다
        if (expectedToken == null || sentToken == null) {
            return false;
        }
        // 비교에 걸리는 시간으로 토큰을 짐작하지 못하게 상수 시간으로 비교한다
        return MessageDigest.isEqual(expectedToken, sentToken.getBytes(StandardCharsets.UTF_8));
    }
}
