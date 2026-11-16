package com.whiteeyes.user.config;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.DefaultRedirectStrategy;
import org.springframework.security.web.RedirectStrategy;
import org.springframework.security.web.access.AccessDeniedHandler;
import tools.jackson.databind.json.JsonMapper;

import com.whiteeyes.common.security.ApiRequests;
import com.whiteeyes.common.security.ForbiddenHandler;

/**
 * 사용자 앱에서 권한이 모자란 요청을 처리한다(FNC-C-003 처리 순서 3, FNC-U-003 처리 순서 2).
 * 승인 대기 계정의 화면 요청만 승인 대기 화면으로 보내고, 나머지(API 403·그 밖의 화면 403)는 공통 ForbiddenHandler에 맡긴다.
 * 승인 대기 화면(/pending)은 사용자 앱에만 있어 이 분기를 common으로 옮기지 않는다.
 */
public class PendingAccessDeniedHandler implements AccessDeniedHandler {

    // 승인 대기 분기에 해당하지 않는 거부는 common의 공통 처리기에 위임한다.
    private final ForbiddenHandler forbiddenHandler;
    // Spring Security 기본 리다이렉트 방식(컨텍스트 경로를 붙여 302로 보낸다).
    private final RedirectStrategy redirectStrategy = new DefaultRedirectStrategy();

    /**
     * @param jsonMapper 공통 처리기가 API 403 응답을 JSON으로 쓸 때 쓴다
     */
    public PendingAccessDeniedHandler(JsonMapper jsonMapper) {
        this.forbiddenHandler = new ForbiddenHandler(jsonMapper);
    }

    /**
     * 권한이 모자란 요청을 처리한다. 승인 대기 계정의 화면 요청이면 /pending으로 보내고, 그 밖에는 공통 처리기가 403으로 응답한다.
     */
    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException ex)
            throws IOException {
        // API 요청에는 화면 이동이 의미 없으므로 제외하고, 로그인한 계정이 승인 대기일 때만 안내 화면으로 보낸다.
        if (!ApiRequests.isApi(request)
                && isPending(SecurityContextHolder.getContext().getAuthentication())) {
            redirectStrategy.sendRedirect(request, response, UserSecurityConfig.PENDING_PATH);
            return;
        }
        // 그 밖의 경우: API는 JSON 403, 화면은 403 화면(공통 처리기가 구분한다).
        forbiddenHandler.handle(request, response, ex);
    }

    /**
     * 인증 정보에 승인 대기 권한(ROLE_PENDING)이 있는지 본다. 비로그인(authentication이 null)이면 false다.
     */
    private boolean isPending(Authentication authentication) {
        return authentication != null
                && authentication.getAuthorities().stream()
                        .anyMatch(authority -> UserRoles.PENDING_AUTHORITY.equals(authority.getAuthority()));
    }
}
