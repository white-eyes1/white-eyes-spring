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

    /**
     * 통계 그래프 화면을 보여준다.
     *
     * @param model 템플릿에 admin 여부를 넘기는 모델
     * @return 뷰 이름(공통 템플릿 stats/graph)
     */
    @GetMapping("/stats/graph")
    public String graphPage(Model model) {
        // 공통 템플릿이 사용자용/관리자용을 가르는 값. 사용자 앱이므로 false.
        model.addAttribute("admin", false);
        return "stats/graph";
    }
}
