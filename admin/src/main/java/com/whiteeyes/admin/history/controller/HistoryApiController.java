package com.whiteeyes.admin.history.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.whiteeyes.admin.history.dto.HistoryListItem;
import com.whiteeyes.admin.history.dto.HistorySearchRequest;
import com.whiteeyes.admin.history.service.HistoryListService;
import com.whiteeyes.common.web.PageResponse;

// 최고 관리자만 부를 수 있다. 권한은 AdminSecurityConfig 권한 표(/api/v1/histories/**)가 막고, 관리자는 403이다
/** 작업 기록 조회 API: GET /api/v1/histories(FNC-A-053). */
@RestController
@RequestMapping("/api/v1/histories")
@RequiredArgsConstructor
public class HistoryApiController {

    // 실제 조회는 서비스가 한다. 컨트롤러는 요청 받기와 응답 내보내기만 한다
    private final HistoryListService historyListService;

    /**
     * 작업 기록 목록. 쿼리스트링을 HistorySearchRequest로 묶고 @Valid로 아이디 길이·page·size 범위를 검사한 뒤 서비스에 넘긴다.
     */
    @GetMapping
    public PageResponse<HistoryListItem> list(@Valid @ModelAttribute HistorySearchRequest request) {
        // 조건에 맞는 한 페이지를 그대로 응답한다
        return historyListService.list(request);
    }
}
