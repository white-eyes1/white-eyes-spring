package com.whiteeyes.admin.account.controller;

import com.whiteeyes.admin.account.dto.AdminListItem;
import com.whiteeyes.admin.account.dto.AdminSearchRequest;
import com.whiteeyes.admin.account.service.AdminListService;
import com.whiteeyes.common.web.PageResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 최고 관리자만 부를 수 있다. 권한은 AdminSecurityConfig 권한 표(/api/v1/admins/**)가 막고, 관리자는 403이다
/** 관리자 목록 API: GET /api/v1/admins(FNC-A-003). */
@RestController
@RequestMapping("/api/v1/admins")
@RequiredArgsConstructor
public class AdminApiController {

    private final AdminListService adminListService;

    @GetMapping
    public PageResponse<AdminListItem> list(@Valid @ModelAttribute AdminSearchRequest request) {
        return adminListService.list(request);
    }
}
