package com.whiteeyes.common.security;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.DefaultRedirectStrategy;
import org.springframework.security.web.RedirectStrategy;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;

/**
 * 로그인 성공 뒤 마지막 로그인 시각을 남기고 상태에 맞는 화면으로 보낸다(FNC-C-001 처리 순서 5·6).
 * 이동 순서: 승인 대기(PENDING)면 승인 대기 화면, 비밀번호 변경이 필요하면 변경 화면, 그 외 통계 메인.
 */
public class LoginSuccessHandler implements AuthenticationSuccessHandler {

    // 승인 대기 계정에 붙는 권한 이름
    private static final String PENDING_ROLE = "ROLE_PENDING";
    private static final String PENDING_URL = "/pending";
    private static final String PASSWORD_CHANGE_URL = "/me/password";
    private static final String STATS_URL = "/stats";

    // 앱별 계정 테이블에 마지막 로그인 시각을 쓰는 구현체
    private final LoginRecorder loginRecorder;
    private final RedirectStrategy redirectStrategy = new DefaultRedirectStrategy();

    public LoginSuccessHandler(LoginRecorder loginRecorder) {
        this.loginRecorder = loginRecorder;
    }

    @Override
    public void onAuthenticationSuccess(
            HttpServletRequest request, HttpServletResponse response, Authentication authentication)
            throws IOException {
        // 로그인 아이디(Authentication 이름)로 마지막 로그인 시각을 남긴다
        loginRecorder.record(authentication.getName());
        // 앞서 실패한 적이 있으면 남아 있는 실패 문구·아이디를 지운다
        clearFailureAttributes(request);
        // 컨텍스트 경로(관리자 앱은 /admin)는 redirect 전략이 붙인다
        redirectStrategy.sendRedirect(request, response, targetUrl(authentication));
    }

    private String targetUrl(Authentication authentication) {
        // 이동 우선순위 1: 승인 대기 여부
        boolean pending = authentication.getAuthorities().stream()
                .anyMatch(authority -> PENDING_ROLE.equals(authority.getAuthority()));
        if (pending) {
            return PENDING_URL;
        }
        // 이동 우선순위 2: 비밀번호를 바꿔야 하는 계정
        if (authentication.getPrincipal() instanceof AccountPrincipal principal && principal.isMustChangePassword()) {
            return PASSWORD_CHANGE_URL;
        }
        // 그 외는 통계 메인
        return STATS_URL;
    }

    /** 세션에 남은 로그인 실패 흔적을 지운다. 세션이 없으면 아무것도 하지 않는다. */
    private void clearFailureAttributes(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.removeAttribute(LoginAttributes.ERROR_MESSAGE);
            session.removeAttribute(LoginAttributes.LOGIN_ID);
        }
    }
}
