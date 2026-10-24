package com.whiteeyes.common.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 로그인 화면(SCR-C-001, FNC-C-001). 두 앱이 같은 템플릿을 쓰고 앱 이름만 다르게 보인다.
 * 로그인 처리를 만든 앱(Boot 보안 자동 설정이 있는 앱)에만 등록한다. 두 앱 모두 보안 설정에서 POST /login(formLogin)을 처리한다.
 * 회원가입 링크는 사용자 앱에만 보인다(SCR-C-001 '회원가입', login.signup-link).
 */
@Controller
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(name = "org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration")
public class LoginPageController {

    private static final String STATS_REDIRECT = "redirect:/stats";
    private static final String LOGIN_VIEW = "login";

    private final String appName;
    private final boolean signupLink;

    public LoginPageController(
            @Value("${spring.application.name:white-eyes}") String appName,
            @Value("${login.signup-link:false}") boolean signupLink) {
        this.appName = appName;
        this.signupLink = signupLink;
    }

    @GetMapping("/login")
    public String loginPage(Authentication authentication, HttpServletRequest request, Model model) {
        // 로그인한 상태로 들어오면 통계 메인으로 보낸다
        if (isLoggedIn(authentication)) {
            return STATS_REDIRECT;
        }
        model.addAttribute("appName", appName);
        model.addAttribute("signupLink", signupLink);
        // 실패 직후 한 번만 보이도록 꺼내면서 지운다
        HttpSession session = request.getSession(false);
        if (session != null) {
            model.addAttribute("errorMessage", session.getAttribute(LoginAttributes.ERROR_MESSAGE));
            model.addAttribute("loginId", session.getAttribute(LoginAttributes.LOGIN_ID));
            session.removeAttribute(LoginAttributes.ERROR_MESSAGE);
            session.removeAttribute(LoginAttributes.LOGIN_ID);
        }
        return LOGIN_VIEW;
    }

    private boolean isLoggedIn(Authentication authentication) {
        return authentication != null
                && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken);
    }
}
