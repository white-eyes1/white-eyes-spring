package com.whiteeyes.common.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.DefaultRedirectStrategy;
import org.springframework.security.web.RedirectStrategy;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;

/**
 * 로그인 성공 뒤 마지막 로그인 시각을 남기고 상태에 맞는 화면으로 보낸다(FNC-C-001 처리 순서 5·6).
 * 이동 순서: 승인 대기(PENDING)면 승인 대기 화면, 비밀번호 변경이 필요하면 변경 화면, 그 외 통계 메인.
 */
public class LoginSuccessHandler implements AuthenticationSuccessHandler {

    private static final String PENDING_ROLE = "ROLE_PENDING";
    private static final String PENDING_URL = "/pending";
    private static final String PASSWORD_CHANGE_URL = "/me/password";
    private static final String STATS_URL = "/stats";

    private final LoginRecorder loginRecorder;
    private final RedirectStrategy redirectStrategy = new DefaultRedirectStrategy();

    public LoginSuccessHandler(LoginRecorder loginRecorder) {
        this.loginRecorder = loginRecorder;
    }

    @Override
    public void onAuthenticationSuccess(
            HttpServletRequest request, HttpServletResponse response, Authentication authentication)
            throws IOException {
        loginRecorder.record(authentication.getName());
        clearFailureAttributes(request);
        // 컨텍스트 경로(관리자 앱은 /admin)는 redirect 전략이 붙인다
        redirectStrategy.sendRedirect(request, response, targetUrl(authentication));
    }

    private String targetUrl(Authentication authentication) {
        boolean pending = authentication.getAuthorities().stream()
                .anyMatch(authority -> PENDING_ROLE.equals(authority.getAuthority()));
        if (pending) {
            return PENDING_URL;
        }
        if (authentication.getPrincipal() instanceof AccountPrincipal principal && principal.isMustChangePassword()) {
            return PASSWORD_CHANGE_URL;
        }
        return STATS_URL;
    }

    private void clearFailureAttributes(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.removeAttribute(LoginAttributes.ERROR_MESSAGE);
            session.removeAttribute(LoginAttributes.LOGIN_ID);
        }
    }
}
