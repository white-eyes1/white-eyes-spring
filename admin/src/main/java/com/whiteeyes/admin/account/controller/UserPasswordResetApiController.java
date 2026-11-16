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

    // 재설정 업무(탈퇴 계정 거부·임시 비밀번호·세션 삭제·작업 기록)는 서비스가 맡는다
    private final UserPasswordResetService userPasswordResetService;

    /** 임시 비밀번호를 발급해 한 번만 돌려준다. 없는 사용자는 404다. */
    @PostMapping("/{id}/reset-password")
    public PasswordResetResponse resetPassword(@PathVariable("id") Long id) {
        // 임시 비밀번호는 이 응답에서 한 번만 보인다
        return userPasswordResetService.reset(id);
    }
}
