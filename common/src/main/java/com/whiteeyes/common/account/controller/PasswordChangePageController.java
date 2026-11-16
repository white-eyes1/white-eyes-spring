package com.whiteeyes.common.account.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import com.whiteeyes.common.security.AccountPrincipal;
import com.whiteeyes.common.security.LoginAccountResolver;

/**
 * 비밀번호 변경 화면(SCR-C-002 비밀번호 변경 영역, FNC-C-007). 사용자 앱 /me/password, 관리자 앱 /admin/me/password.
 * 비밀번호 변경이 강제된 계정(must_change_password=1)은 이 화면만 쓸 수 있어 안내 문구를 보이고 메뉴를 숨긴다.
 */
@Controller
@RequiredArgsConstructor
public class PasswordChangePageController {

    // 관리자 앱 계정에 붙는 권한 이름. 화면이 관리자 앱용인지 가리는 데 쓴다
    private static final String ROLE_ADMIN = "ROLE_ADMIN";
    private static final String ROLE_SUPER_ADMIN = "ROLE_SUPER_ADMIN";

    private final LoginAccountResolver loginAccountResolver;

    /**
     * 비밀번호 변경 화면(템플릿 account/password)을 보여 준다.
     * forced는 강제 변경 안내 문구, admin은 두 앱이 같은 템플릿을 쓰기 위한 앱 구분 값이다.
     */
    @GetMapping("/me/password")
    public String passwordPage(Model model) {
        // 로그인하지 않은 요청은 보안 설정이 로그인 화면으로 보내 여기까지 오지 않는다. 계정 번호가 없는 옛 세션만 401로 거부한다
        loginAccountResolver.require();
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        // 강제 변경 계정이면 안내 문구를 보이고 메뉴를 숨긴다
        model.addAttribute("forced", mustChangePassword(authentication));
        // 템플릿이 링크 경로(/me/password, /admin/me/password)를 고르도록 앱 종류를 알려 준다
        model.addAttribute("admin", isAdmin(authentication));
        return "account/password";
    }

    /** 로그인 주체가 AccountPrincipal이고 비밀번호 변경이 강제된 상태인지 본다. 다른 형태의 주체면 강제 아님으로 취급한다. */
    private boolean mustChangePassword(Authentication authentication) {
        return authentication.getPrincipal() instanceof AccountPrincipal principal && principal.isMustChangePassword();
    }

    // 두 앱이 같은 템플릿을 쓰므로 로그인한 계정의 역할로 관리자 앱인지 가린다(사용자 앱 계정에는 관리자 역할이 없음)
    private boolean isAdmin(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .anyMatch(a -> ROLE_ADMIN.equals(a.getAuthority()) || ROLE_SUPER_ADMIN.equals(a.getAuthority()));
    }
}
