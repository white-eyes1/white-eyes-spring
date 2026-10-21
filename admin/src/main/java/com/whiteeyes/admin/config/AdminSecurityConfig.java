package com.whiteeyes.admin.config;

import com.whiteeyes.admin.account.service.AdminLoginRecordService;
import com.whiteeyes.admin.account.service.AdminUserDetailsService;
import com.whiteeyes.common.security.ForbiddenHandler;
import com.whiteeyes.common.security.InternalTokenFilter;
import com.whiteeyes.common.security.LoginFailureHandler;
import com.whiteeyes.common.security.LoginRequiredEntryPoint;
import com.whiteeyes.common.security.LoginSuccessHandler;
import com.whiteeyes.common.security.PasswordChangeRequiredFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsChecker;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import tools.jackson.databind.json.JsonMapper;

/**
 * 관리자 앱 로그인(FNC-C-001)과 접근 권한 검사(FNC-C-003).
 * 필터 체인은 둘이다. /internal/** 은 세션 없이 내부 토큰으로만, 나머지는 로그인한 관리자의 역할로 권한 표를 검사한다.
 * 경로는 컨텍스트 경로(/admin)를 뗀 것이다.
 */
@Configuration
// 웹 서버 없이 띄우는 실행(--job=… 같은 1회 작업)에는 HTTP 보안이 필요 없다
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class AdminSecurityConfig {

    private static final String LOGIN_PATH = "/login";
    private static final String INTERNAL_PATH = "/internal/**";
    private static final String ROLE_ADMIN = "ADMIN";
    private static final String ROLE_SUPER_ADMIN = "SUPER_ADMIN";
    // 권한 표(관리자 앱): 공개 경로와 정적 파일, 오류 화면
    private static final String[] PUBLIC_PATHS = {LOGIN_PATH, "/css/**", "/js/**", "/images/**", "/favicon.ico", "/error"};
    // 권한 표(관리자 앱): SUPER_ADMIN만
    private static final String[] SUPER_ADMIN_PATHS = {
        "/admins/**", "/api/v1/admins/**", "/histories/**", "/api/v1/histories/**"
    };

    @Bean
    @Order(1)
    public SecurityFilterChain adminInternalFilterChain(
            HttpSecurity http, JsonMapper jsonMapper, @Value("${internal-api.token}") String internalToken)
            throws Exception {
        // 세션을 쓰지 않고 토큰 헤더로 인증하므로 CSRF와 로그인 화면 설정이 필요 없다. 토큰 확인은 InternalTokenFilter가 한다
        http.securityMatcher(INTERNAL_PATH)
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(requests -> requests.anyRequest().permitAll())
                .addFilterBefore(new InternalTokenFilter(internalToken, jsonMapper), AuthorizationFilter.class);
        return http.build();
    }

    @Bean
    @Order(2)
    public SecurityFilterChain adminWebFilterChain(
            HttpSecurity http,
            AuthenticationProvider adminAuthenticationProvider,
            AdminLoginRecordService loginRecordService,
            JsonMapper jsonMapper)
            throws Exception {
        http.authenticationProvider(adminAuthenticationProvider)
                .authorizeHttpRequests(requests -> requests.requestMatchers(PUBLIC_PATHS)
                        .permitAll()
                        .requestMatchers(SUPER_ADMIN_PATHS)
                        .hasRole(ROLE_SUPER_ADMIN)
                        .anyRequest()
                        .hasAnyRole(ROLE_ADMIN, ROLE_SUPER_ADMIN))
                .formLogin(form -> form.loginPage(LOGIN_PATH)
                        .loginProcessingUrl(LOGIN_PATH)
                        .successHandler(new LoginSuccessHandler(loginRecordService))
                        .failureHandler(new LoginFailureHandler()))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(new LoginRequiredEntryPoint(jsonMapper))
                        .accessDeniedHandler(new ForbiddenHandler(jsonMapper)))
                // 권한 표를 보기 전에 비밀번호 변경이 필요한 계정을 먼저 걸러낸다
                .addFilterBefore(new PasswordChangeRequiredFilter(jsonMapper), AuthorizationFilter.class);
        return http.build();
    }

    @Bean
    public AuthenticationProvider adminAuthenticationProvider(
            AdminUserDetailsService userDetailsService, PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        // 기본은 비밀번호를 보기 전에 비활성화를 판정한다. 비활성화 문구가 계정 존재를 드러내지 않도록
        // 비밀번호가 맞은 뒤에만 판정한다
        provider.setPreAuthenticationChecks(user -> {});
        provider.setPostAuthenticationChecks(disabledAfterPasswordCheck());
        return provider;
    }

    private UserDetailsChecker disabledAfterPasswordCheck() {
        return user -> {
            if (!user.isEnabled()) {
                throw new DisabledException("비활성화된 계정입니다");
            }
        };
    }
}
