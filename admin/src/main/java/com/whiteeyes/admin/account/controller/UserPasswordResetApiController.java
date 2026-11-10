package com.whiteeyes.admin.account.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.whiteeyes.admin.account.dto.PasswordResetResponse;
import com.whiteeyes.admin.account.service.UserPasswordResetService;

/**
 * 사용자 비밀번호 재설정 API: POST /api/v1/users/{id}/reset-password(FNC-A-005).
 */
@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserPasswordResetApiController {

    private final UserPasswordResetService userPasswordResetService;

    /** 임시 비밀번호를 발급해 한 번만 돌려준다. 없는 사용자는 404다. */
    @PostMapping("/{id}/reset-password")
    public PasswordResetResponse resetPassword(@PathVariable("id") Long id) {
        return userPasswordResetService.reset(id);
    }
}
