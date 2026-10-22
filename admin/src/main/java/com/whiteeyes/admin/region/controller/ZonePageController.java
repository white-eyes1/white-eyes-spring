package com.whiteeyes.admin.region.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 구역 관리 화면 SCR-A-004(FNC-C-008 지역·구역 목록, FNC-A-013 구역 미리보기, FNC-A-014 구역 등록),
 * 관리자 앱 주소 /admin/zones. 데이터는 화면 JS가 API로 받는다.
 */
@Controller
public class ZonePageController {

    @GetMapping("/zones")
    public String zonesPage() {
        return "admin/zones";
    }
}
