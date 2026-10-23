package com.whiteeyes.common.account.controller;

import com.whiteeyes.common.security.AccountPrincipal;
import com.whiteeyes.common.security.LoginAccountResolver;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 비밀번호 변경 화면(SCR-C-002 비밀번호 변경 영역, FNC-C-007). 사용자 앱 /me/password, 관리자 앱 /admin/me/password.
 * 비밀번호 변경이 강제된 계정(must_change_password=1)은 이 화면만 쓸 수 있어 안내 문구를 보이고 메뉴를 숨긴다.
 */
@Controller
public class PasswordChangePageController {

    private static final String ROLE_ADMIN = "ROLE_ADMIN";
    private static final String ROLE_SUPER_ADMIN = "ROLE_SUPER_ADMIN";

    private final LoginAccountResolver loginAccountResolver;

    public PasswordChangePageController(LoginAccountResolver loginAccountResolver) {
        this.loginAccountResolver = loginAccountResolver;
    }

    @GetMapping("/me/password")
    public String passwordPage(Model model) {
        // 로그인하지 않은 요청은 보안 설정이 로그인 화면으로 보내 여기까지 오지 않는다. 계정 번호가 없는 옛 세션만 401로 거부한다
        loginAccountResolver.require();
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        model.addAttribute("forced", mustChangePassword(authentication));
        model.addAttribute("admin", isAdmin(authentication));
        return "account/password";
    }

    private boolean mustChangePassword(Authentication authentication) {
        return authentication.getPrincipal() instanceof AccountPrincipal principal && principal.isMustChangePassword();
    }

    // 두 앱이 같은 템플릿을 쓰므로 로그인한 계정의 역할로 관리자 앱인지 가린다(사용자 앱 계정에는 관리자 역할이 없음)
    private boolean isAdmin(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .anyMatch(a -> ROLE_ADMIN.equals(a.getAuthority()) || ROLE_SUPER_ADMIN.equals(a.getAuthority()));
    }
}
