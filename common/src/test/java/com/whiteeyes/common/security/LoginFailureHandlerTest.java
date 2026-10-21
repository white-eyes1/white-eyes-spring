package com.whiteeyes.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Collections;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

/**
 * 로그인 실패 문구와 입력 아이디 유지(FNC-C-001).
 */
class LoginFailureHandlerTest {

    private static final String INVALID_MESSAGE = "아이디 또는 비밀번호가 올바르지 않습니다.";
    private static final String DISABLED_MESSAGE = "사용이 중지된 계정입니다.";
    private static final String RAW_PASSWORD = "secret-never-stored";

    private final LoginFailureHandler handler = new LoginFailureHandler();

    private MockHttpServletRequest fail(AuthenticationException exception, String loginId) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/admin/login");
        request.setContextPath("/admin");
        request.setParameter("username", loginId);
        request.setParameter("password", RAW_PASSWORD);
        MockHttpServletResponse response = new MockHttpServletResponse();
        handler.onAuthenticationFailure(request, response, exception);
        assertThat(response.getStatus()).isEqualTo(302);
        assertThat(response.getRedirectedUrl()).isEqualTo("/admin/login?error");
        return request;
    }

    @Test
    @DisplayName("FNC-C-001 아이디가 없어도 틀린 비밀번호와 같은 문구를 쓴다")
    void failure_unknownAccount_sameMessageAsBadPassword() throws Exception {
        MockHttpServletRequest notFound = fail(new UsernameNotFoundException("x"), "nobody");
        MockHttpServletRequest badPassword = fail(new BadCredentialsException("x"), "hong01");

        assertThat(notFound.getSession().getAttribute(LoginAttributes.ERROR_MESSAGE)).isEqualTo(INVALID_MESSAGE);
        assertThat(badPassword.getSession().getAttribute(LoginAttributes.ERROR_MESSAGE))
                .isEqualTo(INVALID_MESSAGE);
    }

    @Test
    @DisplayName("FNC-C-001 비활성화된 계정은 사용 중지 문구를 쓴다")
    void failure_disabled_usesDisabledMessage() throws Exception {
        MockHttpServletRequest request = fail(new DisabledException("x"), "hong01");

        assertThat(request.getSession().getAttribute(LoginAttributes.ERROR_MESSAGE)).isEqualTo(DISABLED_MESSAGE);
    }

    @Test
    @DisplayName("FNC-C-001 실패해도 입력한 아이디는 남기고 비밀번호는 남기지 않는다")
    void failure_keepsLoginIdOnly() throws Exception {
        MockHttpServletRequest request = fail(new BadCredentialsException("x"), "hong01");

        assertThat(request.getSession().getAttribute(LoginAttributes.LOGIN_ID)).isEqualTo("hong01");
        assertThat(Collections.list(request.getSession().getAttributeNames()))
                .noneMatch(name -> String.valueOf(request.getSession().getAttribute(name)).contains(RAW_PASSWORD));
    }

    @Test
    @DisplayName("FNC-C-001 아주 긴 아이디는 입력 최대 길이(20자)까지만 남긴다")
    void failure_longLoginId_truncated() throws Exception {
        MockHttpServletRequest request = fail(new BadCredentialsException("x"), "a".repeat(100));

        assertThat((String) request.getSession().getAttribute(LoginAttributes.LOGIN_ID)).hasSize(20);
    }
}
