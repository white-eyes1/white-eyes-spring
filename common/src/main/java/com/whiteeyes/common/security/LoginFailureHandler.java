package com.whiteeyes.common.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.DefaultRedirectStrategy;
import org.springframework.security.web.RedirectStrategy;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;

/**
 * 로그인 실패 시 /login?error로 보내고, 화면에 보일 문구와 입력한 아이디를 세션에 잠깐 남긴다(FNC-C-001).
 * 거부 사유와 관계없이 문구는 하나로 통일해 계정이 있는지 드러내지 않는다. 단 DISABLED만 별도 문구를 쓴다.
 */
public class LoginFailureHandler implements AuthenticationFailureHandler {

    private static final String INVALID_MESSAGE = "아이디 또는 비밀번호가 올바르지 않습니다.";
    private static final String DISABLED_MESSAGE = "사용이 중지된 계정입니다.";
    private static final String FAILURE_URL = "/login?error";
    // 아이디 입력 칸의 최대 길이(SCR-C-001). 그보다 긴 값은 칸에 다시 채우지 않는다
    private static final int LOGIN_ID_MAX_LENGTH = 20;
    private static final String LOGIN_ID_PARAMETER = "username";

    private final RedirectStrategy redirectStrategy = new DefaultRedirectStrategy();

    @Override
    public void onAuthenticationFailure(
            HttpServletRequest request, HttpServletResponse response, AuthenticationException exception)
            throws IOException {
        String message = exception instanceof DisabledException ? DISABLED_MESSAGE : INVALID_MESSAGE;
        request.getSession().setAttribute(LoginAttributes.ERROR_MESSAGE, message);
        request.getSession().setAttribute(LoginAttributes.LOGIN_ID, truncatedLoginId(request));
        redirectStrategy.sendRedirect(request, response, FAILURE_URL);
    }

    private String truncatedLoginId(HttpServletRequest request) {
        String loginId = request.getParameter(LOGIN_ID_PARAMETER);
        if (loginId == null) {
            return "";
        }
        return loginId.length() > LOGIN_ID_MAX_LENGTH ? loginId.substring(0, LOGIN_ID_MAX_LENGTH) : loginId;
    }
}
