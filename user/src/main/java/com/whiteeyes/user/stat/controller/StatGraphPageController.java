package com.whiteeyes.user.stat.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 통계 그래프 화면 SCR-C-004(FNC-C-012), 사용자 앱 주소 /stats/graph.
 * 화면은 두 앱이 함께 쓰는 common 템플릿(stats/graph)이고, 대상·단위·기간은 주소 쿼리를 화면 JS가 읽는다.
 */
@Controller
public class StatGraphPageController {

    @GetMapping("/stats/graph")
    public String graphPage(Model model) {
        model.addAttribute("admin", false);
        return "stats/graph";
    }
}
