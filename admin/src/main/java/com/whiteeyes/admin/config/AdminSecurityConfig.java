package com.whiteeyes.admin.config;

import org.springframework.beans.factory.annotation.Value;
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

import com.whiteeyes.admin.account.service.AdminLoginRecordService;
import com.whiteeyes.admin.account.service.AdminUserDetailsService;
import com.whiteeyes.admin.media.controller.LiveAuthFilter;
import com.whiteeyes.admin.media.service.LiveAuthService;
import com.whiteeyes.common.security.ForbiddenHandler;
import com.whiteeyes.common.security.InternalTokenFilter;
import com.whiteeyes.common.security.LoginFailureHandler;
import com.whiteeyes.common.security.LoginRequiredEntryPoint;
import com.whiteeyes.common.security.LoginSuccessHandler;
import com.whiteeyes.common.security.LogoutSupport;
import com.whiteeyes.common.security.PasswordChangeRequiredFilter;

/**
 * 관리자 앱 로그인(FNC-C-001), 로그아웃(FNC-C-002), 접근 권한 검사(FNC-C-003).
 * 필터 체인은 둘이다. /internal/** 은 세션 없이 내부 토큰으로만, 나머지는 로그인한 관리자의 역할로 권한 표를 검사한다.
 * 경로는 컨텍스트 경로(/admin)를 뗀 것이다.
 */
@Configuration
public class AdminSecurityConfig {

    // 아래 경로는 모두 컨텍스트 경로(/admin)를 뗀 값이다.
    private static final String LOGIN_PATH = "/login";
    private static final String INTERNAL_PATH = "/internal/**";
    private static final String LIVE_AUTH_PATH = "/api/v1/live/auth";
    // hasRole(...)에는 ROLE_ 접두어 없는 이름을 쓴다(스프링이 ROLE_을 붙여 비교한다).
    private static final String ROLE_ADMIN = "ADMIN";
    private static final String ROLE_SUPER_ADMIN = "SUPER_ADMIN";
    // 권한 표(관리자 앱): 공개 경로와 정적 파일, 오류 화면
    private static final String[] PUBLIC_PATHS = {
        LOGIN_PATH, "/css/**", "/js/**", "/images/**", "/favicon.ico", "/error"
    };
    // 권한 표(관리자 앱): SUPER_ADMIN만
    private static final String[] SUPER_ADMIN_PATHS = {
        "/admins/**", "/api/v1/admins/**", "/histories/**", "/api/v1/histories/**"
    };

    /**
     * 실시간 화면 권한 확인(FNC-A-041)은 Spring Security가 세션을 읽거나 갱신하지 않게 별도 체인으로 뺀다.
     * LiveAuthFilter가 쿠키의 세션을 저장소에서 직접 조회하고 401·403·204로만 답한다(nginx auth_request가 그 밖의 응답을 500으로 바꿈).
     */
    @Bean
    @Order(0)
    public SecurityFilterChain adminLiveAuthFilterChain(HttpSecurity http, LiveAuthService liveAuthService)
            throws Exception {
        // 이 체인은 실시간 권한 확인 경로에만 적용한다(@Order가 낮을수록 먼저 매칭된다).
        http.securityMatcher(LIVE_AUTH_PATH)
                .csrf(AbstractHttpConfigurer::disable)
                .securityContext(AbstractHttpConfigurer::disable)
                // 익명 사용자 필터와 세션 관리 필터는 request.getSession(false)·getRequestedSessionId()로 세션을 읽는다.
                // 읽힌 세션은 응답 뒤 저장되어 마지막 접근 시각이 갱신되므로 이 경로에서는 둘 다 끈다
                .anonymous(AbstractHttpConfigurer::disable)
                .sessionManagement(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(requests -> requests.anyRequest().permitAll())
                // 컨트롤러(MVC)까지 가면 세션이 읽혀 저장되므로 필터가 직접 답한다
                .addFilterBefore(new LiveAuthFilter(liveAuthService), AuthorizationFilter.class);
        return http.build();
    }

    /**
     * 내부 API(/internal/**) 필터 체인. 사람 로그인이 아니라 서버끼리의 호출이므로 세션 없이 내부 토큰 헤더만으로 인증한다.
     * 분석 서버 같은 내부 호출자가 쓰는 경로다.
     */
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

    /**
     * 관리자 웹 화면·API 필터 체인(나머지 모든 경로). 폼 로그인과 세션을 쓰고, 경로별 역할 권한 표를 적용한다.
     * 공개 경로는 누구나, 관리자 계정 관리·이력은 SUPER_ADMIN만, 그 밖은 ADMIN 또는 SUPER_ADMIN이 접근한다.
     */
    @Bean
    @Order(2)
    public SecurityFilterChain adminWebFilterChain(
            HttpSecurity http,
            AuthenticationProvider adminAuthenticationProvider,
            AdminLoginRecordService loginRecordService,
            JsonMapper jsonMapper)
            throws Exception {
        // 요청 매칭 순서가 중요하다: 공개 경로 허용 -> SUPER_ADMIN 전용 경로 -> 그 밖의 모든 경로는 관리자 이상. 앞에서 매칭된 규칙이 우선한다.
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
                // 명세 처리 순서대로 권한 표(순서 3)를 통과한 요청에만 비밀번호 변경 필요 여부(순서 4)를 본다
                .addFilterAfter(new PasswordChangeRequiredFilter(jsonMapper), AuthorizationFilter.class)
                // 로그인 성공 뒤 이동은 LoginSuccessHandler가 정해 저장한 요청을 쓰지 않으므로, 비로그인 요청마다 세션이 생기지 않게 끈다
                .requestCache(AbstractHttpConfigurer::disable)
                .logout(logout -> logout.logoutSuccessHandler(LogoutSupport.successHandler()))
                .csrf(csrf -> csrf.ignoringRequestMatchers(LogoutSupport.logoutWithoutSession()));
        return http.build();
    }

    /**
     * 아이디·비밀번호 인증기. 비활성 계정 판정을 비밀번호 확인 뒤로 미뤄, 틀린 비밀번호로는 계정 존재 여부를 알 수 없게 한다.
     */
    @Bean
    public AuthenticationProvider adminAuthenticationProvider(
            AdminUserDetailsService userDetailsService, PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        // 기본은 비밀번호를 보기 전에 비활성화를 판정한다. 비활성화 문구가 계정 존재를 드러내지 않도록
        // 비밀번호가 맞은 뒤에만 판정한다
        // 인증 전 검사를 비워 둔다(기본 검사는 비활성·잠김 등을 비밀번호 확인 전에 판정한다).
        provider.setPreAuthenticationChecks(user -> {});
        // 비밀번호가 맞은 뒤에만 비활성 여부를 검사한다.
        provider.setPostAuthenticationChecks(disabledAfterPasswordCheck());
        return provider;
    }

    /** 비밀번호 확인 뒤 실행되는 검사: 비활성화된 계정이면 로그인을 거부한다. */
    private UserDetailsChecker disabledAfterPasswordCheck() {
        return user -> {
            if (!user.isEnabled()) {
                throw new DisabledException("비활성화된 계정입니다");
            }
        };
    }
}
