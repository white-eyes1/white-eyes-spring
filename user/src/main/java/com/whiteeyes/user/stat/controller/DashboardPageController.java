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

    /**
     * 통계 메인 화면을 보여준다.
     *
     * @param model 템플릿에 admin 여부를 넘기는 모델
     * @return 뷰 이름(공통 템플릿 stats/main)
     */
    @GetMapping("/stats")
    public String dashboardPage(Model model) {
        // 공통 템플릿이 이 값으로 사용자용/관리자용 차이(헤더, 캠 패널 등)를 가른다. 사용자 앱이므로 false.
        model.addAttribute("admin", false);
        return "stats/main";
    }
}
