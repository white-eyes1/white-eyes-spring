package com.whiteeyes.admin.event.controller;

import com.whiteeyes.admin.event.dto.EventExportRequest;
import com.whiteeyes.admin.event.dto.EventListItem;
import com.whiteeyes.admin.event.dto.EventSearchRequest;
import com.whiteeyes.admin.event.dto.EventSummaryResponse;
import com.whiteeyes.admin.event.service.EventExportService;
import com.whiteeyes.admin.event.service.EventExportService.EventExportFile;
import com.whiteeyes.admin.event.service.EventListService;
import com.whiteeyes.admin.event.service.EventSummaryService;
import com.whiteeyes.common.web.PageResponse;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 이벤트 API: 목록 GET /api/v1/events, 요약 GET /api/v1/events/summary(FNC-A-036), 엑셀 GET /api/v1/events/export
 * (FNC-A-039).
 */
@RestController
@RequestMapping("/api/v1/events")
@RequiredArgsConstructor
public class EventApiController {

    private static final String XLSX_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private final EventListService eventListService;
    private final EventSummaryService eventSummaryService;
    private final EventExportService eventExportService;

    @GetMapping
    public PageResponse<EventListItem> list(@Valid @ModelAttribute EventSearchRequest request) {
        return eventListService.list(request);
    }

    @GetMapping("/summary")
    public EventSummaryResponse summary(@Valid @ModelAttribute EventSearchRequest request) {
        return eventSummaryService.summarize(request);
    }

    // 컨텍스트 경로가 붙어 /admin/api/v1/events/export. 이벤트 번호 경로(/{id})보다 글자가 같은 이 경로가 먼저 맞는다
    @GetMapping("/export")
    public void export(@ModelAttribute EventExportRequest request, HttpServletResponse response) throws IOException {
        try (EventExportFile file = eventExportService.export(request)) {
            response.setContentType(XLSX_TYPE);
            response.setHeader(
                    HttpHeaders.CONTENT_DISPOSITION,
                    ContentDisposition.attachment()
                            .filename(file.fileName(), StandardCharsets.UTF_8)
                            .build()
                            .toString());
            file.writeTo(response.getOutputStream());
        }
    }
}
