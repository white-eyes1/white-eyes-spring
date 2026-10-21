package com.whiteeyes.admin.support;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;

import com.whiteeyes.common.security.AccountPrincipal;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * 작업 기록(FNC-C-026)은 행동한 계정을 로그인 정보에서 채우므로, 구역·캠을 만들거나 가동·정지하는 테스트는
 * 로그인한 관리자로 실행한다. 로그인 자체(세션)는 AdminLoginTest가 확인한다.
 */
public final class AdminTestLogin {

    public static final long ADMIN_ID = 7L;
    public static final String ADMIN_LOGIN_ID = "test-admin";

    private AdminTestLogin() {}

    /** MockMvc 요청을 로그인한 관리자의 요청으로 만든다. */
    public static RequestPostProcessor admin() {
        return authentication(token());
    }

    /** 로그인 아이디를 정해 쓰는 경우(작업 기록 칸 길이 같은 경계 확인용). */
    public static RequestPostProcessor admin(String loginId) {
        return authentication(token(loginId));
    }

    /** MockMvc를 거치지 않고 서비스를 바로 부르는 테스트에서 현재 스레드를 로그인한 상태로 만든다. 끝나면 {@link #clear()}. */
    public static void loginForDirectCalls() {
        SecurityContextHolder.getContext().setAuthentication(token());
    }

    public static void clear() {
        SecurityContextHolder.clearContext();
    }

    private static UsernamePasswordAuthenticationToken token() {
        return token(ADMIN_LOGIN_ID);
    }

    private static UsernamePasswordAuthenticationToken token(String loginId) {
        AccountPrincipal principal = new AccountPrincipal(
                ADMIN_ID, loginId, "encoded", true, false, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        return UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities());
    }
}
