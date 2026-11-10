package com.whiteeyes.admin.history.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

// 최고 관리자만 들어온다. 권한은 AdminSecurityConfig 권한 표(/histories/**)가 막고, 관리자는 403 화면이다
/**
 * 작업 기록 화면 SCR-A-012(FNC-A-053), 관리자 앱 주소 /admin/histories.
 * 표의 데이터는 화면 JS가 GET /api/v1/histories로 받아 그린다.
 */
@Controller
public class HistoryPageController {

    @GetMapping("/histories")
    public String histories() {
        return "admin/histories";
    }
}
