package com.whiteeyes.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * 로그인 성공 뒤 이동과 마지막 로그인 시각 기록(FNC-C-001).
 */
class LoginSuccessHandlerTest {

    private final List<String> recordedLoginIds = new ArrayList<>();
    private final LoginSuccessHandler handler = new LoginSuccessHandler(recordedLoginIds::add);

    private Authentication authentication(boolean mustChangePassword, String role) {
        AccountPrincipal principal = new AccountPrincipal(
                1L, "hong01", "encoded", true, mustChangePassword, List.of(new SimpleGrantedAuthority(role)));
        return UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities());
    }

    private String redirectLocation(Authentication authentication, String contextPath) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", contextPath + "/login");
        request.setContextPath(contextPath);
        MockHttpServletResponse response = new MockHttpServletResponse();
        handler.onAuthenticationSuccess(request, response, authentication);
        assertThat(response.getStatus()).isEqualTo(302);
        return response.getRedirectedUrl();
    }

    @Test
    @DisplayName("FNC-C-001 일반 계정은 통계 메인으로 이동한다")
    void success_normalAccount_redirectsToStats() throws Exception {
        assertThat(redirectLocation(authentication(false, "ROLE_ADMIN"), "/admin")).isEqualTo("/admin/stats");
    }

    @Test
    @DisplayName("FNC-C-001 must_change_password=1이면 비밀번호 변경 화면으로 이동한다")
    void success_mustChangePassword_redirectsToPasswordChange() throws Exception {
        assertThat(redirectLocation(authentication(true, "ROLE_ADMIN"), "/admin")).isEqualTo("/admin/me/password");
    }

    @Test
    @DisplayName("FNC-C-001 PENDING 권한이면 승인 대기 화면으로 이동한다")
    void success_pending_redirectsToPendingPage() throws Exception {
        assertThat(redirectLocation(authentication(false, "ROLE_PENDING"), "")).isEqualTo("/pending");
    }

    @Test
    @DisplayName("FNC-C-001 PENDING이면 비밀번호 변경이 필요해도 승인 대기 화면이 먼저다")
    void success_pendingAndMustChange_pendingFirst() throws Exception {
        assertThat(redirectLocation(authentication(true, "ROLE_PENDING"), "")).isEqualTo("/pending");
    }

    @Test
    @DisplayName("FNC-C-001 로그인에 성공하면 그 계정의 마지막 로그인 시각 기록을 부른다")
    void success_recordsLastLogin() throws Exception {
        redirectLocation(authentication(false, "ROLE_ADMIN"), "/admin");

        assertThat(recordedLoginIds).containsExactly("hong01");
    }

    @Test
    @DisplayName("FNC-C-001 로그인에 성공하면 앞서 실패하며 남긴 오류 문구와 아이디를 지운다")
    void success_clearsFailureAttributes() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/login");
        request.getSession().setAttribute(LoginAttributes.ERROR_MESSAGE, "x");
        request.getSession().setAttribute(LoginAttributes.LOGIN_ID, "y");

        handler.onAuthenticationSuccess(request, new MockHttpServletResponse(), authentication(false, "ROLE_ADMIN"));

        assertThat(request.getSession().getAttribute(LoginAttributes.ERROR_MESSAGE)).isNull();
        assertThat(request.getSession().getAttribute(LoginAttributes.LOGIN_ID)).isNull();
    }
}
