package com.whiteeyes.admin.account.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

// 최고 관리자만 들어온다. 권한은 AdminSecurityConfig 권한 표(/admins/**)가 막고, 관리자는 403 화면이다
/** 관리자 관리 화면(SCR-A-003, FNC-A-003). 목록 데이터는 화면 JS가 GET /api/v1/admins로 받아 채운다. */
@Controller
public class AdminPageController {

    /** 관리자 관리 화면 템플릿(admin/admins)을 돌려준다. 데이터 없이 틀만 렌더링하고 목록은 JS가 API로 채운다. */
    @GetMapping("/admins")
    public String admins() {
        // 뷰 이름이다. Thymeleaf가 templates/admin/admins.html을 찾아 렌더링한다
        return "admin/admins";
    }
}
