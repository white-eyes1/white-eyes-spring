package com.whiteeyes.admin.stat.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 기간 비교 분석 화면 SCR-C-005(FNC-C-020), 관리자 앱 주소 /admin/stats/compare.
 * 화면은 두 앱이 함께 쓰는 common 템플릿(stats/compare) 하나이고, 대상·기간은 주소 쿼리를 화면 JS가 읽는다.
 */
@Controller
public class ComparePageController {

    @GetMapping("/stats/compare")
    public String comparePage(Model model) {
        model.addAttribute("admin", true);
        return "stats/compare";
    }
}
