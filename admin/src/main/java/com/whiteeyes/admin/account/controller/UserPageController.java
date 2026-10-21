package com.whiteeyes.admin.account.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** 사용자 관리 화면(SCR-A-001, FNC-A-002). 목록 데이터는 화면 JS가 GET /api/v1/users로 받아 채운다. */
@Controller
public class UserPageController {

    @GetMapping("/users")
    public String users() {
        return "admin/users";
    }
}
