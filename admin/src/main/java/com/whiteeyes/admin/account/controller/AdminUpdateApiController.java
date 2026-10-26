package com.whiteeyes.admin.account.controller;

import com.whiteeyes.admin.account.dto.AdminListItem;
import com.whiteeyes.admin.account.dto.AdminUpdateRequest;
import com.whiteeyes.admin.account.service.AdminUpdateService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 최고 관리자만 부를 수 있다. 권한은 AdminSecurityConfig 권한 표(/api/v1/admins/**)가 막고, 관리자는 403이다
/** 관리자 정보 수정 API: PATCH /api/v1/admins/{id}(FNC-A-010). */
@RestController
@RequestMapping("/api/v1/admins")
@RequiredArgsConstructor
public class AdminUpdateApiController {

    private final AdminUpdateService adminUpdateService;

    /** 이름·메일·연락처를 고치고 수정된 관리자를 돌려준다. 형식 오류는 400(fieldErrors), 없는 관리자는 404다. */
    @PatchMapping("/{id}")
    public AdminListItem update(@PathVariable("id") Long id, @Valid @RequestBody AdminUpdateRequest request) {
        return adminUpdateService.update(id, request);
    }
}
