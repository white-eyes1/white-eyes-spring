package com.whiteeyes.admin.config;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;
import tools.jackson.databind.json.JsonMapper;

import com.whiteeyes.common.exception.ErrorCode;
import com.whiteeyes.common.security.SecurityErrorWriter;

/**
 * /internal/** 은 내부 포트로만, 내부 포트로는 /internal/** 만 받는다(FNC-C-003, conventions.md '모듈·포트').
 * 두 포트가 같은 서블릿 컨텍스트를 공유하므로 어느 포트로 들어왔는지로 갈라낸다. 맞지 않으면 없는 경로처럼 404를 준다.
 * 보안 필터보다 앞에서 돌아 인증 여부와 상관없이 경로가 존재하는지도 드러내지 않는다.
 */
public class InternalPortFilter extends OncePerRequestFilter {

    private static final String INTERNAL_PATH = "/internal";

    // 보안 필터가 보는 경로와 같게 %인코딩을 풀어서 판단한다
    // 서블릿 컨텍스트 경로를 뗀 앱 내부 경로를 구하는 도구.
    private final UrlPathHelper pathHelper = new UrlPathHelper();
    // 내부 통신 포트 번호. 요청이 들어온 로컬 포트와 비교한다.
    private final int internalPort;
    // 내부 API 경로에 대한 404를 JSON 본문으로 쓰기 위한 매퍼.
    private final JsonMapper jsonMapper;

    public InternalPortFilter(int internalPort, JsonMapper jsonMapper) {
        this.internalPort = internalPort;
        this.jsonMapper = jsonMapper;
    }

    /**
     * 요청이 들어온 포트와 요청 경로의 짝을 검사한다. 내부 포트에는 /internal/** 만, 웹 포트에는 그 외 경로만 허용한다.
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        // 요청이 어느 포트로 들어왔는지(내부 포트인지).
        boolean onInternalPort = request.getLocalPort() == internalPort;
        // 요청 경로가 /internal 계열인지.
        boolean internalPath = isInternalPath(request);
        // 두 값이 같으면(내부 포트+내부 경로, 웹 포트+일반 경로) 정상 짝이므로 다음 필터로 넘긴다. 다르면 아래에서 404로 막는다.
        if (onInternalPort == internalPath) {
            filterChain.doFilter(request, response);
            return;
        }
        if (internalPath) {
            // 내부 API를 부르는 쪽은 JSON 오류 응답을 기대한다(conventions.md '인증 오류')
            // 웹 포트로 들어온 /internal 요청도 404로 답해 내부 API가 존재한다는 사실을 숨긴다.
            SecurityErrorWriter.write(response, jsonMapper, ErrorCode.NOT_FOUND);
            return;
        }
        // 내부 포트로 들어온 일반 화면 요청: 화면용 기본 404 처리.
        response.sendError(HttpServletResponse.SC_NOT_FOUND);
    }

    /** /internal 이거나 /internal/ 아래인 경로인지. startsWith("/internal")만 쓰면 /internalx 같은 경로도 걸려서 '/'를 붙여 비교한다. */
    private boolean isInternalPath(HttpServletRequest request) {
        String path = pathHelper.getPathWithinApplication(request);
        return path.equals(INTERNAL_PATH) || path.startsWith(INTERNAL_PATH + "/");
    }
}
