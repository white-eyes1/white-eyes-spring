package com.whiteeyes.common.account.controller;

import com.whiteeyes.common.security.AccountPrincipal;
import com.whiteeyes.common.security.LoginAccountResolver;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 내 정보 화면(SCR-C-002 내 정보 영역, FNC-C-006). 사용자 앱 /me, 관리자 앱 /admin/me.
 * 값은 화면이 GET /api/v1/me로 받아 채운다(screens.md). 비밀번호 변경 영역(FNC-C-007)을 같은 화면에 함께 둔다.
 * 비밀번호 변경이 강제된 계정은 PasswordChangeRequiredFilter가 먼저 /me/password로 보낸다.
 */
@Controller
public class MyInfoPageController {

    private static final String ROLE_ADMIN = "ROLE_ADMIN";
    private static final String ROLE_SUPER_ADMIN = "ROLE_SUPER_ADMIN";

    private final LoginAccountResolver loginAccountResolver;

    public MyInfoPageController(LoginAccountResolver loginAccountResolver) {
        this.loginAccountResolver = loginAccountResolver;
    }

    @GetMapping("/me")
    public String myInfoPage(Model model) {
        // 계정 번호가 없는 옛 세션만 401로 거부한다(로그인하지 않은 요청은 보안 설정이 로그인 화면으로 보냄)
        loginAccountResolver.require();
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        model.addAttribute("admin", isAdmin(authentication));
        model.addAttribute("forced", authentication.getPrincipal() instanceof AccountPrincipal p && p.isMustChangePassword());
        return "account/me";
    }

    // 두 앱이 같은 템플릿을 쓰므로 로그인한 계정의 역할로 관리자 앱인지 가린다
    private boolean isAdmin(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .anyMatch(a -> ROLE_ADMIN.equals(a.getAuthority()) || ROLE_SUPER_ADMIN.equals(a.getAuthority()));
    }
}
