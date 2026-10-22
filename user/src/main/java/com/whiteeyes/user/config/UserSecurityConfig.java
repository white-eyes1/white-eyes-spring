package com.whiteeyes.user.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authorization.AuthorityAuthorizationManager;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorizationManagers;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import tools.jackson.databind.json.JsonMapper;

/**
 * 사용자 앱 보안 설정. 지금은 승인 대기 계정을 승인 대기 화면 안에 가두는 일만 한다(FNC-U-003).
 * 아직 없는 부분은 각 이슈가 이 설정을 넓힌다.
 * <ul>
 *   <li>#3 로그인(FNC-C-001): formLogin과 common의 LoginSuccessHandler·LoginFailureHandler 연결</li>
 *   <li>#4 접근 권한 검사(FNC-C-003): '그 외' 규칙을 권한 표(ROLE_USER)로 바꾸고,
 *       세션 없음은 화면 /login·API 401</li>
 *   <li>#22 로그아웃(FNC-C-002): 지금은 Spring 기본 로그아웃(POST /logout → /login?logout)</li>
 * </ul>
 */
@Configuration
// 웹 서버 없이 띄우는 실행에는 HTTP 보안이 필요 없다(AdminSecurityConfig와 같음)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class UserSecurityConfig {

    static final String PENDING_PATH = "/pending";
    // 권한 표(FNC-C-003)의 공개 경로와 정적 파일, 오류 화면
    private static final String[] PUBLIC_PATHS = {
        "/login", "/signup", "/api/v1/signup/**", "/css/**", "/js/**", "/images/**", "/favicon.ico", "/error"
    };

    @Bean
    public SecurityFilterChain userFilterChain(HttpSecurity http, JsonMapper jsonMapper) throws Exception {
        // #3: 여기에 formLogin(...)을 붙인다. 성공 처리는 LoginSuccessHandler가 ROLE_PENDING을 /pending으로 보낸다
        http.authorizeHttpRequests(requests -> requests
                        .requestMatchers(PUBLIC_PATHS).permitAll()
                        .requestMatchers(PENDING_PATH).hasRole(UserRoles.PENDING)
                        // #4: 권한 표가 생기면 hasRole(UserRoles.USER)로 바꾼다. 그 전까지는 승인 대기 계정만 막는다
                        .anyRequest().access(notPending()))
                .exceptionHandling(exceptions ->
                        exceptions.accessDeniedHandler(new PendingAccessDeniedHandler(jsonMapper)));
        return http.build();
    }

    private AuthorizationManager<RequestAuthorizationContext> notPending() {
        return AuthorizationManagers.not(AuthorityAuthorizationManager.hasRole(UserRoles.PENDING));
    }
}
