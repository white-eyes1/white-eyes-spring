package com.whiteeyes.user.config;

import com.whiteeyes.common.security.ApiRequests;
import com.whiteeyes.common.security.ForbiddenHandler;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.DefaultRedirectStrategy;
import org.springframework.security.web.RedirectStrategy;
import org.springframework.security.web.access.AccessDeniedHandler;
import tools.jackson.databind.json.JsonMapper;

/**
 * 사용자 앱에서 권한이 모자란 요청을 처리한다(FNC-C-003 처리 순서 3, FNC-U-003 처리 순서 2).
 * 승인 대기 계정의 화면 요청만 승인 대기 화면으로 보내고, 나머지(API 403·그 밖의 화면 403)는 공통 ForbiddenHandler에 맡긴다.
 * 승인 대기 화면(/pending)은 사용자 앱에만 있어 이 분기를 common으로 옮기지 않는다.
 */
public class PendingAccessDeniedHandler implements AccessDeniedHandler {

    private final ForbiddenHandler forbiddenHandler;
    private final RedirectStrategy redirectStrategy = new DefaultRedirectStrategy();

    public PendingAccessDeniedHandler(JsonMapper jsonMapper) {
        this.forbiddenHandler = new ForbiddenHandler(jsonMapper);
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException ex)
            throws IOException {
        if (!ApiRequests.isApi(request) && isPending(SecurityContextHolder.getContext().getAuthentication())) {
            redirectStrategy.sendRedirect(request, response, UserSecurityConfig.PENDING_PATH);
            return;
        }
        forbiddenHandler.handle(request, response, ex);
    }

    private boolean isPending(Authentication authentication) {
        return authentication != null
                && authentication.getAuthorities().stream()
                        .anyMatch(authority -> UserRoles.PENDING_AUTHORITY.equals(authority.getAuthority()));
    }
}
