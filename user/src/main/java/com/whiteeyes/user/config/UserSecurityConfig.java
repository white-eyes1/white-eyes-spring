package com.whiteeyes.user.config;

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

import com.whiteeyes.common.security.LoginFailureHandler;
import com.whiteeyes.common.security.LoginRequiredEntryPoint;
import com.whiteeyes.common.security.LoginSuccessHandler;
import com.whiteeyes.common.security.LogoutSupport;
import com.whiteeyes.common.security.PasswordChangeRequiredFilter;
import com.whiteeyes.user.account.service.UserAccountDetailsService;
import com.whiteeyes.user.account.service.UserLoginRecordService;

/**
 * 사용자 앱 로그인(FNC-C-001), 로그아웃(FNC-C-002), 접근 권한 검사(FNC-C-003), 승인 대기 안내(FNC-U-003).
 * 권한 표(사용자 앱): 공개 /login·/signup·/api/v1/signup/**, PENDING은 /pending·/logout, USER는 그 외 전부.
 */
@Configuration
public class UserSecurityConfig {

    // PendingAccessDeniedHandler가 같은 패키지에서 이동 주소로 쓰므로 package-private으로 연다.
    static final String PENDING_PATH = "/pending";
    private static final String LOGIN_PATH = "/login";
    private static final String SIGNUP_PATH = "/signup";
    // 권한 표(FNC-C-003)의 공개 경로와 정적 파일, 오류 화면
    private static final String[] PUBLIC_PATHS = {
        LOGIN_PATH, SIGNUP_PATH, "/api/v1/signup/**", "/css/**", "/js/**", "/images/**", "/favicon.ico", "/error"
    };

    /**
     * 사용자 앱의 보안 필터 체인. 인증(로그인·로그아웃)과 주소별 권한 규칙을 정한다.
     *
     * @param userAuthenticationProvider 사용자 계정 인증기
     * @param loginRecordService 로그인 성공 때 마지막 로그인 시각을 남기는 서비스
     * @param jsonMapper API 오류 응답을 JSON으로 쓸 때 쓰는 매퍼
     */
    @Bean
    public SecurityFilterChain userFilterChain(
            HttpSecurity http,
            AuthenticationProvider userAuthenticationProvider,
            UserLoginRecordService loginRecordService,
            JsonMapper jsonMapper)
            throws Exception {
        // 인증은 아래 userAuthenticationProvider(user_account 기준)만 쓴다.
        http.authenticationProvider(userAuthenticationProvider)
                // 주소별 권한 규칙. 위에서부터 먼저 맞는 규칙이 적용되므로 순서가 중요하다.
                .authorizeHttpRequests(requests -> requests
                        // 승인 대기 중에는 가입 화면도 열 수 없다(SCR-U-003). 거부되면 PendingAccessDeniedHandler가 /pending으로 보낸다
                        .requestMatchers(SIGNUP_PATH)
                        .access((authentication, context) ->
                                new AuthorizationDecision(authentication.get().getAuthorities().stream()
                                        .noneMatch(a -> UserRoles.PENDING_AUTHORITY.equals(a.getAuthority()))))
                        // 로그인 전에도 쓰는 공개 주소(가입 API, 정적 파일, 오류 화면 등).
                        .requestMatchers(PUBLIC_PATHS)
                        .permitAll()
                        // /logout은 로그아웃 필터가 권한 검사 전에 처리하므로 PENDING도 쓸 수 있다
                        // 승인 대기 안내 화면은 PENDING 역할만 연다.
                        .requestMatchers(PENDING_PATH)
                        .hasRole(UserRoles.PENDING)
                        // 나머지 모든 주소는 승인된 사용자(USER)만 쓸 수 있다.
                        .anyRequest()
                        .hasRole(UserRoles.USER))
                // 성공 처리(LoginSuccessHandler)가 ROLE_PENDING은 /pending, 비밀번호 변경 필요는 /me/password로 보낸다
                // 폼 로그인. 같은 주소(/login)가 화면(GET)과 처리(POST)를 함께 맡는다.
                .formLogin(form -> form.loginPage(LOGIN_PATH)
                        .loginProcessingUrl(LOGIN_PATH)
                        .successHandler(new LoginSuccessHandler(loginRecordService))
                        .failureHandler(new LoginFailureHandler()))
                // 로그인하지 않은 요청은 로그인 안내로, 권한이 모자란 요청은 승인 대기 분기를 포함한 처리기로 보낸다.
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(new LoginRequiredEntryPoint(jsonMapper))
                        .accessDeniedHandler(new PendingAccessDeniedHandler(jsonMapper)))
                // 명세 처리 순서대로 권한 표(순서 3)를 통과한 요청에만 비밀번호 변경 필요 여부(순서 4)를 본다
                .addFilterAfter(new PasswordChangeRequiredFilter(jsonMapper), AuthorizationFilter.class)
                // 로그인 성공 뒤 이동은 LoginSuccessHandler가 정해 저장한 요청을 쓰지 않으므로, 비로그인 요청마다 세션이 생기지 않게 끈다
                .requestCache(AbstractHttpConfigurer::disable)
                // 로그아웃 성공 응답은 common의 공통 처리를 쓴다.
                .logout(logout -> logout.logoutSuccessHandler(LogoutSupport.successHandler()))
                // 세션 없이 들어오는 로그아웃 요청(LogoutSupport.logoutWithoutSession)은 CSRF 검사 대상에서 뺀다.
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
        // UserDetailsService로 계정을 읽고 PasswordEncoder로 비밀번호를 비교하는 표준 인증기.
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        return provider;
    }
}
