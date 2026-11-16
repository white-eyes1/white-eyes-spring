package com.whiteeyes.common.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 로그인 화면(SCR-C-001, FNC-C-001). 두 앱이 같은 템플릿을 쓰고 앱 이름만 다르게 보인다.
 * 로그인 처리를 만든 앱(Boot 보안 자동 설정이 있는 앱)에만 등록한다. 두 앱 모두 보안 설정에서 POST /login(formLogin)을 처리한다.
 * 회원가입 링크는 사용자 앱에만 보인다(SCR-C-001 '회원가입', login.signup-link).
 */
@Controller
@ConditionalOnClass(name = "org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration")
public class LoginPageController {

    // 통계 메인으로의 이동. 컨텍스트 경로는 스프링이 붙인다
    private static final String STATS_REDIRECT = "redirect:/stats";
    private static final String LOGIN_VIEW = "login";
    // ?signup=done이면 가입 완료 안내를 보인다
    private static final String SIGNUP_DONE = "done";

    // 화면에 보이는 앱 이름. 설정(spring.application.name)에서 오며 없으면 white-eyes
    private final String appName;
    // 회원가입 링크를 보일지. 설정(login.signup-link)으로 사용자 앱에서만 켠다
    private final boolean signupLink;

    public LoginPageController(
            @Value("${spring.application.name:white-eyes}") String appName,
            @Value("${login.signup-link:false}") boolean signupLink) {
        this.appName = appName;
        this.signupLink = signupLink;
    }

    /**
     * 로그인 화면을 보여 준다. 이미 로그인한 사용자는 통계 메인으로 보내고, 로그인에 실패해 돌아온 경우에는
     * 세션에 남겨 둔 실패 문구와 아이디를 모델로 옮긴다.
     */
    @GetMapping("/login")
    public String loginPage(
            Authentication authentication,
            HttpServletRequest request,
            @RequestParam(name = "signup", required = false) String signup,
            Model model) {
        // 로그인한 상태로 들어오면 통계 메인으로 보낸다
        if (isLoggedIn(authentication)) {
            return STATS_REDIRECT;
        }
        model.addAttribute("appName", appName);
        model.addAttribute("signupLink", signupLink);
        // 가입을 마치고 넘어온 경우(?signup=done)에만 완료 안내를 보인다. 회원가입이 있는 사용자 앱에서만
        model.addAttribute("signupDone", signupLink && SIGNUP_DONE.equals(signup));
        // 실패 직후 한 번만 보이도록 꺼내면서 지운다
        // false를 주어 세션이 없을 때 새로 만들지 않는다(로그인 화면을 보기만 하는데 세션이 생기지 않게)
        HttpSession session = request.getSession(false);
        if (session != null) {
            model.addAttribute("errorMessage", session.getAttribute(LoginAttributes.ERROR_MESSAGE));
            model.addAttribute("loginId", session.getAttribute(LoginAttributes.LOGIN_ID));
            session.removeAttribute(LoginAttributes.ERROR_MESSAGE);
            session.removeAttribute(LoginAttributes.LOGIN_ID);
        }
        return LOGIN_VIEW;
    }

    // 앱 주소만 입력해도(/, 관리자 앱 /admin/) 통계 메인으로 보낸다. 로그인 전이면 보안 설정이 로그인 화면으로 보낸다
    /** 루트 주소 접근을 통계 메인으로 보낸다. */
    @GetMapping("/")
    public String root() {
        return STATS_REDIRECT;
    }

    /** 실제로 로그인한 상태인지. 스프링 시큐리티는 로그인 전에도 익명 인증을 넣어 두므로 익명은 제외한다. */
    private boolean isLoggedIn(Authentication authentication) {
        return authentication != null
                && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken);
    }
}
