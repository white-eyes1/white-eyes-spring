package com.whiteeyes.user.account.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** 회원가입 화면(SCR-U-001, FNC-U-002). */
@Controller
public class SignupPageController {

    /**
     * 회원가입 화면을 보여준다. 입력 확인과 가입 요청은 화면 JS가 /api/v1/signup 쪽 API로 보낸다.
     *
     * @return 뷰 이름(templates/user/signup)
     */
    @GetMapping("/signup")
    public String signupPage() {
        return "user/signup";
    }
}
