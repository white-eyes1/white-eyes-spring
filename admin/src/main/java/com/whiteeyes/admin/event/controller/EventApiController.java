package com.whiteeyes.admin.event.controller;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.whiteeyes.admin.event.dto.EventDetailResponse;
import com.whiteeyes.admin.event.dto.EventExportRequest;
import com.whiteeyes.admin.event.dto.EventListItem;
import com.whiteeyes.admin.event.dto.EventSearchRequest;
import com.whiteeyes.admin.event.dto.EventStatusUpdateRequest;
import com.whiteeyes.admin.event.dto.EventSummaryResponse;
import com.whiteeyes.admin.event.service.EventDetailService;
import com.whiteeyes.admin.event.service.EventExportService;
import com.whiteeyes.admin.event.service.EventExportService.EventExportFile;
import com.whiteeyes.admin.event.service.EventListService;
import com.whiteeyes.admin.event.service.EventStatusService;
import com.whiteeyes.admin.event.service.EventSummaryService;
import com.whiteeyes.common.event.dto.UncheckedCountResponse;
import com.whiteeyes.common.event.service.UncheckedEventCountService;
import com.whiteeyes.common.web.PageResponse;

/**
 * 이벤트 API: 목록 GET /api/v1/events, 요약 /summary(FNC-A-036), 상세 /{id}(FNC-A-037), 엑셀 /export(FNC-A-039),
 * 처리 상태 변경 PATCH /{id}/status(FNC-A-038), 미확인 수 /unchecked-count(FNC-A-048).
 */
@RestController
@RequestMapping("/api/v1/events")
@RequiredArgsConstructor
public class EventApiController {

    // 엑셀(.xlsx) 응답의 Content-Type. 브라우저가 파일 다운로드로 인식하게 한다
    private static final String XLSX_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    // 아래 서비스들은 모두 생성자 주입(@RequiredArgsConstructor). 컨트롤러는 요청 받기와 응답 내보내기만 하고 실제 처리는 서비스에 맡긴다
    private final EventListService eventListService;
    private final EventSummaryService eventSummaryService;
    private final EventDetailService eventDetailService;
    private final EventExportService eventExportService;
    private final EventStatusService eventStatusService;
    private final UncheckedEventCountService uncheckedEventCountService;

    /**
     * 이벤트 목록 조회(FNC-A-036). 쿼리스트링을 EventSearchRequest로 묶고 @Valid로 page·size 범위를 검사한 뒤 서비스에 넘긴다.
     */
    @GetMapping
    public PageResponse<EventListItem> list(@Valid @ModelAttribute EventSearchRequest request) {
        // 검색 조건에 맞는 한 페이지를 그대로 응답한다
        return eventListService.list(request);
    }

    // 헤더 배지의 첫 숫자. 이벤트 번호 경로(/{id})보다 글자가 같은 이 경로가 먼저 맞는다
    /**
     * 미확인 이벤트 수(FNC-A-048). 공통 모듈의 서비스를 그대로 호출한다.
     */
    @GetMapping("/unchecked-count")
    public UncheckedCountResponse uncheckedCount() {
        return uncheckedEventCountService.count();
    }

    /**
     * 요약 카드용 건수(FNC-A-036). 목록과 같은 조건을 받아 처리 상태별·종류별 건수를 돌려준다.
     */
    @GetMapping("/summary")
    public EventSummaryResponse summary(@Valid @ModelAttribute EventSearchRequest request) {
        return eventSummaryService.summarize(request);
    }

    // 컨텍스트 경로가 붙어 /admin/api/v1/events/export. 이벤트 번호 경로(/{id})보다 글자가 같은 이 경로가 먼저 맞는다
    /**
     * 이벤트 엑셀 내려받기(FNC-A-039). 서비스가 만든 임시 파일을 응답 스트림에 써서 첨부 파일로 내려준다.
     */
    @GetMapping("/export")
    public void export(@ModelAttribute EventExportRequest request, HttpServletResponse response) throws IOException {
        // try-with-resources: 응답을 다 쓰면(예외여도) 임시 파일 같은 자원을 닫아 정리한다
        try (EventExportFile file = eventExportService.export(request)) {
            // 응답 형식을 xlsx로 지정
            response.setContentType(XLSX_TYPE);
            response.setHeader(
                    // attachment로 내려 브라우저가 열지 않고 저장하게 한다. 파일명은 UTF-8로 인코딩해 한글이 깨지지 않게 한다
                    HttpHeaders.CONTENT_DISPOSITION,
                    ContentDisposition.attachment()
                            .filename(file.fileName(), StandardCharsets.UTF_8)
                            .build()
                            .toString());
            // 엑셀 내용을 응답 본문 스트림으로 흘려보낸다
            file.writeTo(response.getOutputStream());
        }
    }

    /**
     * 이벤트 상세(FNC-A-037). 없는 번호면 서비스가 EventNotFoundException(404)을 던진다.
     */
    @GetMapping("/{id}")
    public EventDetailResponse detail(@PathVariable("id") Long id) {
        return eventDetailService.find(id);
    }

    /**
     * 처리 상태 변경(FNC-A-038). 본문을 @Valid로 검사한 뒤 서비스에 넘기고, 바뀐 결과의 상세를 돌려준다.
     * 다른 관리자가 먼저 처리했다면 서비스가 409(STATUS_CONFLICT)를 던진다.
     */
    @PatchMapping("/{id}/status")
    public EventDetailResponse changeStatus(
            @PathVariable("id") Long id, @Valid @RequestBody EventStatusUpdateRequest request) {
        return eventStatusService.changeStatus(id, request);
    }
}
