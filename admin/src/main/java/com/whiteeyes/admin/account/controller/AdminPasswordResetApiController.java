package com.whiteeyes.admin.account.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.whiteeyes.admin.account.dto.PasswordResetResponse;
import com.whiteeyes.admin.account.service.AdminPasswordResetService;

// 최고 관리자만 부를 수 있다. 권한은 AdminSecurityConfig 권한 표(/api/v1/admins/**)가 막고, 관리자는 403이다
/**
 * 관리자 비밀번호 재설정 API: POST /api/v1/admins/{id}/reset-password(FNC-A-010).
 */
@RestController
@RequestMapping("/api/v1/admins")
@RequiredArgsConstructor
public class AdminPasswordResetApiController {

    private final AdminPasswordResetService adminPasswordResetService;

    /** 임시 비밀번호를 발급해 한 번만 돌려준다. 없는 관리자는 404다. */
    @PostMapping("/{id}/reset-password")
    public PasswordResetResponse resetPassword(@PathVariable("id") Long id) {
        return adminPasswordResetService.reset(id);
    }
}
