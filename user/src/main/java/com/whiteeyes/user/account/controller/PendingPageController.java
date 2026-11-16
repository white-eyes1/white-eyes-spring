package com.whiteeyes.user.account.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 승인 대기 안내(FNC-U-003, SCR-U-002). 승인 대기 계정만 들어오고, 다른 주소로 가도 여기로 온다(UserSecurityConfig).
 */
@Controller
public class PendingPageController {

    /**
     * 승인 대기 안내 화면을 보여준다.
     * 접근 제어는 UserSecurityConfig가 맡는다(PENDING 역할만 이 주소를 연다).
     *
     * @return 뷰 이름(templates/user/pending)
     */
    @GetMapping("/pending")
    public String showPendingPage() {
        // 데이터 없이 정적 안내 문구만 보여주는 화면이라 모델에 담는 값이 없다.
        return "user/pending";
    }
}
