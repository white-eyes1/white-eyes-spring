package com.whiteeyes.admin.media.controller;

import com.whiteeyes.admin.media.service.LiveAuthService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 실시간 화면·카드 미리보기 권한 확인(FNC-A-041). nginx의 auth_request가 부르는 GET /api/v1/live/auth 에
 * 204(열어 줌) / 401(로그인 없음) / 403(권한 없음·가동 중 아님)만 돌려준다.
 *
 * <p>컨트롤러가 아니라 필터로 답하는 이유: 스프링 MVC(DispatcherServlet)는 요청마다 세션을 읽어(플래시 맵 관리자)
 * 응답 뒤 세션이 저장되며 마지막 접근 시각이 늘어난다. 2초마다 오는 미리보기 확인이 세션 만료(30분)를 늘리지 않도록
 * MVC까지 가지 않고 이 필터에서 끝낸다.
 */
public class LiveAuthFilter extends OncePerRequestFilter {

    private final LiveAuthService liveAuthService;

    public LiveAuthFilter(LiveAuthService liveAuthService) {
        this.liveAuthService = liveAuthService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (!"GET".equals(request.getMethod())) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return;
        }
        int status =
                switch (liveAuthService.check(request, request.getParameter("cameraId"))) {
                    case OK -> HttpServletResponse.SC_NO_CONTENT;
                    case UNAUTHENTICATED -> HttpServletResponse.SC_UNAUTHORIZED;
                    case FORBIDDEN -> HttpServletResponse.SC_FORBIDDEN;
                };
        response.setStatus(status);
    }
}
