package com.whiteeyes.admin.account.controller;

import com.whiteeyes.admin.account.dto.AdminCreateRequest;
import com.whiteeyes.admin.account.dto.AdminCreateResponse;
import com.whiteeyes.admin.account.dto.AdminListItem;
import com.whiteeyes.admin.account.dto.AdminSearchRequest;
import com.whiteeyes.admin.account.service.AdminCreateService;
import com.whiteeyes.admin.account.service.AdminListService;
import com.whiteeyes.common.web.PageResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

// 최고 관리자만 부를 수 있다. 권한은 AdminSecurityConfig 권한 표(/api/v1/admins/**)가 막고, 관리자는 403이다
/** 관리자 목록·계정 생성 API: GET·POST /api/v1/admins(FNC-A-003·009). */
@RestController
@RequestMapping("/api/v1/admins")
@RequiredArgsConstructor
public class AdminApiController {

    private final AdminListService adminListService;
    private final AdminCreateService adminCreateService;

    @GetMapping
    public PageResponse<AdminListItem> list(@Valid @ModelAttribute AdminSearchRequest request) {
        return adminListService.list(request);
    }

    /** 관리자 계정 생성(FNC-A-009). 임시 비밀번호는 이 응답에서 한 번만 보인다. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AdminCreateResponse create(@Valid @RequestBody AdminCreateRequest request) {
        return adminCreateService.create(request);
    }
}
