package com.whiteeyes.common.security;

import org.springframework.http.HttpMethod;
import org.springframework.security.web.authentication.logout.LogoutSuccessHandler;
import org.springframework.security.web.authentication.logout.SimpleUrlLogoutSuccessHandler;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.AndRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * 두 앱의 로그아웃 설정에 쓰는 부품(FNC-C-002). POST /logout이 세션을 지우고 /login으로 보낸다.
 * 두 앱의 보안 설정이 아래처럼 붙인다.
 * <pre>
 * http.logout(logout -> logout.logoutSuccessHandler(LogoutSupport.successHandler()))
 *     .csrf(csrf -> csrf.ignoringRequestMatchers(LogoutSupport.logoutWithoutSession()));
 * </pre>
 */
public final class LogoutSupport {

    private static final String LOGOUT_PATH = "/logout";
    private static final String LOGIN_PATH = "/login";

    private LogoutSupport() {}

    /** 로그아웃 뒤 로그인 화면으로 이동한다. 관리자 앱은 컨텍스트 경로(/admin)가 앞에 붙는다. */
    public static LogoutSuccessHandler successHandler() {
        SimpleUrlLogoutSuccessHandler handler = new SimpleUrlLogoutSuccessHandler();
        handler.setDefaultTargetUrl(LOGIN_PATH);
        return handler;
    }

    /**
     * 세션이 이미 없는(만료 포함) POST /logout. 확인할 세션이 없으므로 CSRF 검사에서 뺀다.
     * 세션이 있는데 토큰이 틀리면 이 요청이 아니므로 CSRF 검사가 403으로 막는다.
     */
    public static RequestMatcher logoutWithoutSession() {
        RequestMatcher logoutRequest = PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, LOGOUT_PATH);
        // getSession(false)는 POST /logout일 때만 부른다. 다른 요청에서 세션을 읽으면 마지막 접근 시각이 갱신된다(트러블슈팅 0016)
        return new AndRequestMatcher(logoutRequest, request -> request.getSession(false) == null);
    }
}
