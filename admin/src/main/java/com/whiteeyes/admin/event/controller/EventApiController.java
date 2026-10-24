package com.whiteeyes.admin.event.controller;

import com.whiteeyes.admin.event.dto.EventDetailResponse;
import com.whiteeyes.admin.event.dto.EventListItem;
import com.whiteeyes.admin.event.dto.EventSearchRequest;
import com.whiteeyes.admin.event.dto.EventStatusUpdateRequest;
import com.whiteeyes.admin.event.dto.EventSummaryResponse;
import com.whiteeyes.admin.event.service.EventDetailService;
import com.whiteeyes.admin.event.service.EventListService;
import com.whiteeyes.admin.event.service.EventStatusService;
import com.whiteeyes.admin.event.service.EventSummaryService;
import com.whiteeyes.common.web.PageResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 이벤트 API: 목록 GET /api/v1/events, 요약 /summary(FNC-A-036), 상세 /{id}(FNC-A-037),
 * 처리 상태 변경 PATCH /{id}/status(FNC-A-038).
 */
@RestController
@RequestMapping("/api/v1/events")
@RequiredArgsConstructor
public class EventApiController {

    private final EventListService eventListService;
    private final EventSummaryService eventSummaryService;
    private final EventDetailService eventDetailService;
    private final EventStatusService eventStatusService;

    @GetMapping
    public PageResponse<EventListItem> list(@Valid @ModelAttribute EventSearchRequest request) {
        return eventListService.list(request);
    }

    @GetMapping("/summary")
    public EventSummaryResponse summary(@Valid @ModelAttribute EventSearchRequest request) {
        return eventSummaryService.summarize(request);
    }

    @GetMapping("/{id}")
    public EventDetailResponse detail(@PathVariable("id") Long id) {
        return eventDetailService.find(id);
    }

    @PatchMapping("/{id}/status")
    public EventDetailResponse changeStatus(
            @PathVariable("id") Long id, @Valid @RequestBody EventStatusUpdateRequest request) {
        return eventStatusService.changeStatus(id, request);
    }
}
