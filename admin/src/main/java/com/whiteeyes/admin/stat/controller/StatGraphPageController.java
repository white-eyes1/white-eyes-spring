package com.whiteeyes.admin.stat.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 통계 그래프 화면 SCR-C-004(FNC-C-012), 관리자 앱 주소 /admin/stats/graph.
 * 화면은 두 앱이 함께 쓰는 common 템플릿(stats/graph)이고, 대상·단위·기간은 주소 쿼리를 화면 JS가 읽는다.
 */
@Controller
public class StatGraphPageController {

    /** 통계 그래프 화면을 연다. 그래프 데이터는 화면 JS가 통계 API로 불러온다. */
    @GetMapping("/stats/graph")
    public String graphPage(Model model) {
        // common 템플릿에서 관리자 앱일 때만 보이는 부분을 켜는 표시
        model.addAttribute("admin", true);
        // 뷰 이름. 두 앱이 함께 쓰는 common 템플릿이다
        return "stats/graph";
    }
}
