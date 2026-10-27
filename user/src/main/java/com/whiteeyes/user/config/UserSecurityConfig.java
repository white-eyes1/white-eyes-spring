package com.whiteeyes.user.config;

import com.whiteeyes.common.security.LoginFailureHandler;
import com.whiteeyes.common.security.LoginRequiredEntryPoint;
import com.whiteeyes.common.security.LoginSuccessHandler;
import com.whiteeyes.common.security.LogoutSupport;
import com.whiteeyes.common.security.PasswordChangeRequiredFilter;
import com.whiteeyes.user.account.service.UserAccountDetailsService;
import com.whiteeyes.user.account.service.UserLoginRecordService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import tools.jackson.databind.json.JsonMapper;

/**
 * 사용자 앱 로그인(FNC-C-001), 로그아웃(FNC-C-002), 접근 권한 검사(FNC-C-003), 승인 대기 안내(FNC-U-003).
 * 권한 표(사용자 앱): 공개 /login·/signup·/api/v1/signup/**, PENDING은 /pending·/logout, USER는 그 외 전부.
 */
@Configuration
// 웹 서버 없이 띄우는 실행에는 HTTP 보안이 필요 없다(AdminSecurityConfig와 같음)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class UserSecurityConfig {

    static final String PENDING_PATH = "/pending";
    private static final String LOGIN_PATH = "/login";
    private static final String SIGNUP_PATH = "/signup";
    // 권한 표(FNC-C-003)의 공개 경로와 정적 파일, 오류 화면
    private static final String[] PUBLIC_PATHS = {
        LOGIN_PATH, SIGNUP_PATH, "/api/v1/signup/**", "/css/**", "/js/**", "/images/**", "/favicon.ico", "/error"
    };

    @Bean
    public SecurityFilterChain userFilterChain(
            HttpSecurity http,
            AuthenticationProvider userAuthenticationProvider,
            UserLoginRecordService loginRecordService,
            JsonMapper jsonMapper)
            throws Exception {
        http.authenticationProvider(userAuthenticationProvider)
                .authorizeHttpRequests(requests -> requests
                        // 승인 대기 중에는 가입 화면도 열 수 없다(SCR-U-003). 거부되면 PendingAccessDeniedHandler가 /pending으로 보낸다
                        .requestMatchers(SIGNUP_PATH).access((authentication, context) -> new AuthorizationDecision(
                                authentication.get().getAuthorities().stream()
                                        .noneMatch(a -> UserRoles.PENDING_AUTHORITY.equals(a.getAuthority()))))
                        .requestMatchers(PUBLIC_PATHS).permitAll()
                        // /logout은 로그아웃 필터가 권한 검사 전에 처리하므로 PENDING도 쓸 수 있다
                        .requestMatchers(PENDING_PATH).hasRole(UserRoles.PENDING)
                        .anyRequest().hasRole(UserRoles.USER))
                // 성공 처리(LoginSuccessHandler)가 ROLE_PENDING은 /pending, 비밀번호 변경 필요는 /me/password로 보낸다
                .formLogin(form -> form.loginPage(LOGIN_PATH)
                        .loginProcessingUrl(LOGIN_PATH)
                        .successHandler(new LoginSuccessHandler(loginRecordService))
                        .failureHandler(new LoginFailureHandler()))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(new LoginRequiredEntryPoint(jsonMapper))
                        .accessDeniedHandler(new PendingAccessDeniedHandler(jsonMapper)))
                // 명세 처리 순서대로 권한 표(순서 3)를 통과한 요청에만 비밀번호 변경 필요 여부(순서 4)를 본다
                .addFilterAfter(new PasswordChangeRequiredFilter(jsonMapper), AuthorizationFilter.class)
                // 로그인 성공 뒤 이동은 LoginSuccessHandler가 정해 저장한 요청을 쓰지 않으므로, 비로그인 요청마다 세션이 생기지 않게 끈다
                .requestCache(AbstractHttpConfigurer::disable)
                .logout(logout -> logout.logoutSuccessHandler(LogoutSupport.successHandler()))
                .csrf(csrf -> csrf.ignoringRequestMatchers(LogoutSupport.logoutWithoutSession()));
        return http.build();
    }

    /**
     * 사용자 계정에는 DISABLED가 없고 탈퇴 계정은 '아이디 없음'으로 거부하므로(UserAccountDetailsService)
     * 관리자 앱처럼 비활성화 판정을 비밀번호 확인 뒤로 옮길 필요가 없다.
     */
    @Bean
    public AuthenticationProvider userAuthenticationProvider(
            UserAccountDetailsService userDetailsService, PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        return provider;
    }
}
