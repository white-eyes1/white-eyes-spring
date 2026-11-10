package com.whiteeyes.admin.account.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

// 최고 관리자만 들어온다. 권한은 AdminSecurityConfig 권한 표(/admins/**)가 막고, 관리자는 403 화면이다
/** 관리자 관리 화면(SCR-A-003, FNC-A-003). 목록 데이터는 화면 JS가 GET /api/v1/admins로 받아 채운다. */
@Controller
public class AdminPageController {

    @GetMapping("/admins")
    public String admins() {
        return "admin/admins";
    }
}
