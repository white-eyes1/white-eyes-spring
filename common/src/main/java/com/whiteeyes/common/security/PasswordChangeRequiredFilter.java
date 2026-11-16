package com.whiteeyes.common.security;

import java.io.IOException;
import java.util.List;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.DefaultRedirectStrategy;
import org.springframework.security.web.RedirectStrategy;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

import com.whiteeyes.common.exception.ErrorCode;

/**
 * must_change_password=1인 계정은 비밀번호 변경 화면·API·로그아웃 외에는 쓰지 못하게 한다(FNC-C-003 처리 순서 4).
 * 화면은 비밀번호 변경 화면으로 보내고, API는 보낼 화면이 없어 403 공통 오류 응답을 준다.
 * 변경 화면이 쓰는 정적 파일과 오류 화면은 막지 않는다.
 * 승인 대기(ROLE_PENDING) 계정은 보지 않는다. 로그인 뒤 이동처럼(FNC-C-001 처리 순서 6) 승인 대기 화면이 먼저이고,
 * 승인 대기 계정은 변경 화면에 들어갈 권한이 없어 보내면 두 화면 사이를 계속 오가게 된다(#313).
 */
public class PasswordChangeRequiredFilter extends OncePerRequestFilter {

    private static final String PASSWORD_CHANGE_URL = "/me/password";
    private static final String PENDING_ROLE = "ROLE_PENDING";
    // 변경 강제 중에도 허용하는 경로. 변경 화면·API·로그아웃과, 변경 화면이 쓰는 정적 파일·오류 화면이다. /css/** 같은 ** 패턴은 Ant 스타일이다
    private static final List<String> ALLOWED_PATTERNS = List.of(
            PASSWORD_CHANGE_URL,
            "/api/v1/me/password",
            "/logout",
            "/css/**",
            "/js/**",
            "/images/**",
            "/favicon.ico",
            "/error");

    private final JsonMapper jsonMapper;
    // 위 허용 목록의 패턴(/css/** 등)과 경로를 비교하는 도구
    private final AntPathMatcher pathMatcher = new AntPathMatcher();
    private final RedirectStrategy redirectStrategy = new DefaultRedirectStrategy();

    public PasswordChangeRequiredFilter(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    /**
     * 변경이 강제된 계정이 허용 경로 밖으로 가려 하면 API는 403, 화면은 비밀번호 변경 화면으로 보내 막는다.
     * 그 외 요청은 그대로 다음 단계로 넘긴다.
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        // 변경 강제 대상이 아니거나 허용 경로면 아무것도 막지 않는다
        if (!mustChangePassword() || isAllowed(request)) {
            filterChain.doFilter(request, response);
            return;
        }
        // API는 이동할 화면이 없으므로 403 공통 오류를 쓴다
        if (ApiRequests.isApi(request)) {
            SecurityErrorWriter.write(response, jsonMapper, ErrorCode.FORBIDDEN);
            return;
        }
        // 화면은 비밀번호 변경 화면으로 보낸다
        redirectStrategy.sendRedirect(request, response, PASSWORD_CHANGE_URL);
    }

    /** 로그인한 계정이 변경 강제 상태이고 승인 대기 계정이 아닌지. 승인 대기는 대상에서 뺀다(이유는 클래스 설명 참고). */
    private boolean mustChangePassword() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null
                && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof AccountPrincipal principal
                && principal.isMustChangePassword()
                && authentication.getAuthorities().stream()
                        .noneMatch(authority -> PENDING_ROLE.equals(authority.getAuthority()));
    }

    /** 요청 경로가 허용 목록의 패턴 중 하나와 맞는지. 인코딩을 푼 경로로 비교해 우회 표기로 빠져나가지 못하게 한다. */
    private boolean isAllowed(HttpServletRequest request) {
        String path = ApiRequests.pathWithinApplication(request);
        return ALLOWED_PATTERNS.stream().anyMatch(pattern -> pathMatcher.match(pattern, path));
    }
}
