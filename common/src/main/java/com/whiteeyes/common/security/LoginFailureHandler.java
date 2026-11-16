package com.whiteeyes.common.security;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
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

    // 아이디 없음·비밀번호 틀림·승인 대기 등 실패 사유와 관계없이 이 문구 하나만 보여, 어떤 계정이 있는지 짐작하지 못하게 한다
    private static final String INVALID_MESSAGE = "아이디 또는 비밀번호가 올바르지 않습니다.";
    private static final String DISABLED_MESSAGE = "사용이 중지된 계정입니다.";
    private static final String FAILURE_URL = "/login?error";
    // 아이디 입력 칸의 최대 길이(SCR-C-001). 그보다 긴 값은 칸에 다시 채우지 않는다
    private static final int LOGIN_ID_MAX_LENGTH = 20;
    // 로그인 폼의 아이디 입력 칸 이름(Spring Security 기본값)
    private static final String LOGIN_ID_PARAMETER = "username";

    // 컨텍스트 경로(관리자 앱은 /admin)를 자동으로 붙여 이동시키는 도구
    private final RedirectStrategy redirectStrategy = new DefaultRedirectStrategy();

    @Override
    public void onAuthenticationFailure(
            HttpServletRequest request, HttpServletResponse response, AuthenticationException exception)
            throws IOException {
        // 사용 중지된 계정(DisabledException)만 따로 알리고, 나머지는 모두 같은 문구를 쓴다
        String message = exception instanceof DisabledException ? DISABLED_MESSAGE : INVALID_MESSAGE;
        // 실패 문구와 입력했던 아이디를 주소창 대신 세션에 두면 실패 주소를 /login?error로 고정할 수 있다
        request.getSession().setAttribute(LoginAttributes.ERROR_MESSAGE, message);
        request.getSession().setAttribute(LoginAttributes.LOGIN_ID, truncatedLoginId(request));
        // 로그인 화면으로 보낸다. 화면이 세션 값을 한 번 꺼내 쓰고 지운다
        redirectStrategy.sendRedirect(request, response, FAILURE_URL);
    }

    /** 입력한 아이디를 로그인 화면에 되채워 줄 값으로 만든다. 입력 칸 최대 길이를 넘는 값은 잘라 세션에 과한 값이 쌓이지 않게 한다. */
    private String truncatedLoginId(HttpServletRequest request) {
        String loginId = request.getParameter(LOGIN_ID_PARAMETER);
        if (loginId == null) {
            return "";
        }
        return loginId.length() > LOGIN_ID_MAX_LENGTH ? loginId.substring(0, LOGIN_ID_MAX_LENGTH) : loginId;
    }
}
