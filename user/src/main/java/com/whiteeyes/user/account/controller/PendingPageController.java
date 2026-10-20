package com.whiteeyes.user.account.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 승인 대기 안내(FNC-U-003, SCR-U-002). 승인 대기 계정만 들어오고, 다른 주소로 가도 여기로 온다(UserSecurityConfig).
 */
@Controller
public class PendingPageController {

    @GetMapping("/pending")
    public String showPendingPage() {
        return "user/pending";
    }
}
