package com.whiteeyes.admin.region.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 구역 관리 화면 SCR-A-004(FNC-C-008 지역·구역 목록, FNC-A-013 구역 미리보기, FNC-A-014 구역 등록),
 * 관리자 앱 주소 /admin/zones. 데이터는 화면 JS가 API로 받는다.
 */
@Controller
public class ZonePageController {

    /** 구역 관리 화면을 연다. 화면 데이터는 템플릿에 싣지 않고, 화면 JS가 위 REST API를 불러 채운다. */
    @GetMapping("/zones")
    public String zonesPage() {
        // 뷰 이름만 돌려준다. 실제 HTML 템플릿 해석은 Thymeleaf 뷰 리졸버가 한다
        return "admin/zones";
    }
}
