package com.whiteeyes.user.stat.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 통계 메인(대시보드) 화면 SCR-C-003(FNC-C-008·009·016), 사용자 앱 주소 /stats.
 * 화면은 두 앱이 함께 쓰는 common 템플릿(stats/main) 하나이고, admin=false라 사용자 헤더(SCR-U-004)를 쓰고
 * 캠 패널·지역 등록 버튼·실시간 보기 창은 그리지 않는다. 지역(regionId)은 주소 쿼리를 화면 JS가 읽는다.
 */
@Controller
public class DashboardPageController {

    @GetMapping("/stats")
    public String dashboardPage(Model model) {
        model.addAttribute("admin", false);
        return "stats/main";
    }
}
