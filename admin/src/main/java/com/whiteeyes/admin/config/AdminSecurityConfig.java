package com.whiteeyes.admin.config;

import com.whiteeyes.admin.account.service.AdminLoginRecordService;
import com.whiteeyes.admin.account.service.AdminUserDetailsService;
import com.whiteeyes.common.security.LoginFailureHandler;
import com.whiteeyes.common.security.LoginSuccessHandler;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.UserDetailsChecker;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * 관리자 앱 로그인(FNC-C-001): 폼 로그인으로 admin_account를 확인한다.
 * 필터 체인은 로그인 경로(/login)와 관리 API(/api/v1/manage/**)에만 건다. 경로별 접근 권한 검사는 FNC-C-003이 이 설정을 넓혀서 맡는다.
 */
@Configuration
// 웹 서버 없이 띄우는 실행(--job=… 같은 1회 작업)에는 HTTP 보안이 필요 없다
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class AdminSecurityConfig {

    private static final String LOGIN_PATH = "/login";
    private static final String MANAGE_API_PATH = "/api/v1/manage/**";

    @Bean
    public SecurityFilterChain adminLoginFilterChain(
            HttpSecurity http, AuthenticationProvider adminAuthenticationProvider, AdminLoginRecordService loginRecordService)
            throws Exception {
        http.securityMatcher(LOGIN_PATH)
                .authenticationProvider(adminAuthenticationProvider)
                .authorizeHttpRequests(requests -> requests.anyRequest().permitAll())
                .formLogin(form -> form.loginPage(LOGIN_PATH)
                        .loginProcessingUrl(LOGIN_PATH)
                        .successHandler(new LoginSuccessHandler(loginRecordService))
                        .failureHandler(new LoginFailureHandler()));
        return http.build();
    }

    /**
     * 관리 API가 세션의 로그인 정보를 읽을 수 있게 한다(작업 기록의 행동한 계정, FNC-C-026).
     * 접근 허용·거부는 아직 하지 않는다(경로별 권한 검사는 FNC-C-003). 지금까지처럼 CSRF 검사도 걸지 않는다.
     */
    @Bean
    public SecurityFilterChain adminManageFilterChain(HttpSecurity http) throws Exception {
        http.securityMatcher(MANAGE_API_PATH)
                .authorizeHttpRequests(requests -> requests.anyRequest().permitAll())
                .csrf(csrf -> csrf.disable());
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
