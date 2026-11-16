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
 * 내 정보 화면(SCR-C-002 내 정보 영역, FNC-C-006). 사용자 앱 /me, 관리자 앱 /admin/me.
 * 값은 화면이 GET /api/v1/me로 받아 채운다(screens.md). 비밀번호 변경 영역(FNC-C-007)을 같은 화면에 함께 둔다.
 * 비밀번호 변경이 강제된 계정은 PasswordChangeRequiredFilter가 먼저 /me/password로 보낸다.
 */
@Controller
@RequiredArgsConstructor
public class MyInfoPageController {

    // Spring Security가 관리자 앱 계정에 붙이는 권한 이름. 화면이 관리자 앱용인지 가리는 데 쓴다
    private static final String ROLE_ADMIN = "ROLE_ADMIN";
    private static final String ROLE_SUPER_ADMIN = "ROLE_SUPER_ADMIN";

    private final LoginAccountResolver loginAccountResolver;

    /**
     * 내 정보 화면(템플릿 account/me)을 보여 준다. 화면에 두 가지 값을 넘긴다:
     * admin(관리자 앱 화면인지, 메뉴·링크 경로 선택용)과 forced(비밀번호 변경이 강제된 계정인지, 안내 문구용).
     */
    @GetMapping("/me")
    public String myInfoPage(Model model) {
        // 계정 번호가 없는 옛 세션만 401로 거부한다(로그인하지 않은 요청은 보안 설정이 로그인 화면으로 보냄)
        loginAccountResolver.require();
        // 위에서 로그인 여부를 확인했으므로 현재 요청의 인증 정보가 있다
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        // 두 앱이 같은 템플릿을 쓰므로 템플릿이 링크 경로(/me, /admin/me)를 고르도록 알려 준다
        model.addAttribute("admin", isAdmin(authentication));
        // 강제 변경 계정이면 화면에 안내를 보이고 메뉴를 숨긴다. 주체가 AccountPrincipal이 아니면 강제 아님으로 본다
        model.addAttribute(
                "forced", authentication.getPrincipal() instanceof AccountPrincipal p && p.isMustChangePassword());
        // 뷰 이름. 값은 화면이 GET /api/v1/me로 받아 채우므로 모델에 계정 정보를 직접 담지 않는다
        return "account/me";
    }

    // 두 앱이 같은 템플릿을 쓰므로 로그인한 계정의 역할로 관리자 앱인지 가린다
    private boolean isAdmin(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .anyMatch(a -> ROLE_ADMIN.equals(a.getAuthority()) || ROLE_SUPER_ADMIN.equals(a.getAuthority()));
    }
}
