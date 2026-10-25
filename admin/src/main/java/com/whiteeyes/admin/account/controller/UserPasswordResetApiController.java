package com.whiteeyes.admin.account.controller;

import com.whiteeyes.admin.account.dto.PasswordResetResponse;
import com.whiteeyes.admin.account.service.UserPasswordResetService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 사용자 비밀번호 재설정 API: POST /api/v1/users/{id}/reset-password(FNC-A-005).
 * 세션 저장소가 웹 앱에만 있어 웹 서버 없이 띄우는 1회 작업(--job=…)에서는 등록하지 않는다.
 */
@RestController
@RequestMapping("/api/v1/users")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequiredArgsConstructor
public class UserPasswordResetApiController {

    private final UserPasswordResetService userPasswordResetService;

    /** 임시 비밀번호를 발급해 한 번만 돌려준다. 없는 사용자는 404다. */
    @PostMapping("/{id}/reset-password")
    public PasswordResetResponse resetPassword(@PathVariable("id") Long id) {
        return userPasswordResetService.reset(id);
    }
}
