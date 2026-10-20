package com.whiteeyes.user.account.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** 회원가입 화면(SCR-U-001). */
@Controller
public class SignupPageController {

    @GetMapping("/signup")
    public String signupPage() {
        return "user/signup";
    }
}
