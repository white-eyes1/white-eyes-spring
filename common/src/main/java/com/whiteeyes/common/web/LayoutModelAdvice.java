package com.whiteeyes.common.web;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * 화면 틀(templates/fragments/layout.html)의 헤더가 쓰는 값을 화면 요청의 모델에 넣는다(SCR-A-013·SCR-U-004, FNC-C-003).
 * 사용자 메뉴에 로그인 아이디를 보이고, 관리자 관리·작업 기록 메뉴는 최고 관리자에게만 보인다.
 */
@ControllerAdvice(annotations = Controller.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class LayoutModelAdvice {

    private static final String ROLE_SUPER_ADMIN = "ROLE_SUPER_ADMIN";

    /** 로그인 아이디. 로그인하지 않았으면 빈 문자열. */
    @ModelAttribute("currentLoginId")
    public String currentLoginId() {
        Authentication authentication = loggedIn();
        return authentication == null ? "" : authentication.getName();
    }

    /** 최고 관리자 여부. */
    @ModelAttribute("superAdmin")
    public boolean superAdmin() {
        Authentication authentication = loggedIn();
        return authentication != null
                && authentication.getAuthorities().stream().anyMatch(a -> ROLE_SUPER_ADMIN.equals(a.getAuthority()));
    }

    private Authentication loggedIn() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return null;
        }
        return authentication;
    }
}
