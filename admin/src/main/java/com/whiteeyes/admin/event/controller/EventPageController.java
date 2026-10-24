package com.whiteeyes.admin.event.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 이벤트 로그 화면 SCR-A-010 중 요약 카드·필터·이벤트 표(FNC-A-036), 관리자 앱 주소 /admin/events.
 * 카드와 표의 데이터는 화면 JS가 /api/v1/events, /api/v1/events/summary로 받아 그린다.
 */
@Controller
public class EventPageController {

    @GetMapping("/events")
    public String eventsPage() {
        return "admin/events";
    }
}
