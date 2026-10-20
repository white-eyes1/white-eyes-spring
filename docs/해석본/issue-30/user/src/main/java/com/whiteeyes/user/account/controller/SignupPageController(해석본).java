/*
 * [해석본] 원본: user/src/main/java/com/whiteeyes/user/account/controller/SignupPageController.java (feature/30-signup 브랜치)
 * 관련 이슈: #30(FNC-U-002), 화면: SCR-U-001
 *
 * [무엇] /signup 화면을 그리는 화면 컨트롤러. 화면 컨트롤러 이름은 PageController(code-conventions 3-2).
 * [권한] 공개 화면이다. Spring Security가 아직 없어 따로 열 설정이 없다. #4(FNC-C-003)에서 공개 경로로 등록해야 한다.
 */
package com.whiteeyes.user.account.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** 회원가입 화면(SCR-U-001). */
@Controller
public class SignupPageController {

    @GetMapping("/signup")
    public String signupPage() {
        // [해석] templates/user/signup.html. 가입 화면은 사용자 앱에만 있어 user 모듈의 templates/user/ 아래에 둔다(package-structure 2장).
        return "user/signup";
    }
}
