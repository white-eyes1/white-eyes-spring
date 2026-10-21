package com.whiteeyes.admin.stat.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 통계 메인(대시보드) 화면 SCR-C-003(FNC-C-008), 관리자 앱 주소 /admin/stats.
 * 지금은 공통 틀(#136, SCR-A-013 헤더)이 그려지는지 확인하는 빈 본문만 있다. 본문은 #139에서 만든다.
 */
@Controller
public class DashboardPageController {

    @GetMapping("/stats")
    public String dashboardPage() {
        return "admin/stats";
    }
}
