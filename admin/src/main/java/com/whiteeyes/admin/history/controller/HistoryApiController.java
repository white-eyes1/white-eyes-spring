package com.whiteeyes.admin.history.controller;

import com.whiteeyes.admin.history.dto.HistoryListItem;
import com.whiteeyes.admin.history.dto.HistorySearchRequest;
import com.whiteeyes.admin.history.service.HistoryListService;
import com.whiteeyes.common.web.PageResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 최고 관리자만 부를 수 있다. 권한은 AdminSecurityConfig 권한 표(/api/v1/histories/**)가 막고, 관리자는 403이다
/** 작업 기록 조회 API: GET /api/v1/histories(FNC-A-053). */
@RestController
@RequestMapping("/api/v1/histories")
@RequiredArgsConstructor
public class HistoryApiController {

    private final HistoryListService historyListService;

    @GetMapping
    public PageResponse<HistoryListItem> list(@Valid @ModelAttribute HistorySearchRequest request) {
        return historyListService.list(request);
    }
}
