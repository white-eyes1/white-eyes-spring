package com.whiteeyes.admin.account.controller;

import com.whiteeyes.admin.account.dto.PasswordResetResponse;
import com.whiteeyes.admin.account.service.AdminPasswordResetService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 최고 관리자만 부를 수 있다. 권한은 AdminSecurityConfig 권한 표(/api/v1/admins/**)가 막고, 관리자는 403이다
/**
 * 관리자 비밀번호 재설정 API: POST /api/v1/admins/{id}/reset-password(FNC-A-010).
 * 세션 저장소가 웹 앱에만 있어 웹 서버 없이 띄우는 1회 작업(--job=…)에서는 등록하지 않는다.
 */
@RestController
@RequestMapping("/api/v1/admins")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequiredArgsConstructor
public class AdminPasswordResetApiController {

    private final AdminPasswordResetService adminPasswordResetService;

    /** 임시 비밀번호를 발급해 한 번만 돌려준다. 없는 관리자는 404다. */
    @PostMapping("/{id}/reset-password")
    public PasswordResetResponse resetPassword(@PathVariable("id") Long id) {
        return adminPasswordResetService.reset(id);
    }
}
