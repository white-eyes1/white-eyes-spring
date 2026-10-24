package com.whiteeyes.admin.event.controller;

import com.whiteeyes.admin.event.dto.EventListItem;
import com.whiteeyes.admin.event.dto.EventSearchRequest;
import com.whiteeyes.admin.event.dto.EventSummaryResponse;
import com.whiteeyes.admin.event.service.EventListService;
import com.whiteeyes.admin.event.service.EventSummaryService;
import com.whiteeyes.common.web.PageResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 이벤트 목록 API: GET /api/v1/events, GET /api/v1/events/summary(FNC-A-036). */
@RestController
@RequestMapping("/api/v1/events")
@RequiredArgsConstructor
public class EventApiController {

    private final EventListService eventListService;
    private final EventSummaryService eventSummaryService;

    @GetMapping
    public PageResponse<EventListItem> list(@Valid @ModelAttribute EventSearchRequest request) {
        return eventListService.list(request);
    }

    @GetMapping("/summary")
    public EventSummaryResponse summary(@Valid @ModelAttribute EventSearchRequest request) {
        return eventSummaryService.summarize(request);
    }
}
