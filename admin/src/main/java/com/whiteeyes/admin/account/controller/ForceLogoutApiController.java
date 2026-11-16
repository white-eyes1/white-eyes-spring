package com.whiteeyes.admin.account.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.whiteeyes.admin.account.dto.ForceLogoutResponse;
import com.whiteeyes.admin.account.service.ForceLogoutService;

// 사용자 대상은 관리자 이상, 관리자 대상은 최고 관리자만 부를 수 있다. 권한은 AdminSecurityConfig 권한 표(/api/v1/admins/**)가 막고, 관리자는 403이다
/**
 * 강제 로그아웃 API: POST /api/v1/users/{id}/logout, POST /api/v1/admins/{id}/logout(FNC-A-008).
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class ForceLogoutApiController {

    // 사용자·관리자 대상 모두 이 서비스 하나가 처리한다
    private final ForceLogoutService forceLogoutService;

    /** 사용자의 세션을 모두 지운다. 지운 수를 돌려주고, 세션이 없으면 0이다. */
    @PostMapping("/users/{id}/logout")
    public ForceLogoutResponse forceLogoutUser(@PathVariable("id") Long id) {
        // 응답의 deletedSessions는 지운 세션 수다. 접속 중이 아니면 0이다
        return forceLogoutService.logoutUser(id);
    }

    /** 관리자의 세션을 모두 지운다. 자기 자신은 400이다. */
    @PostMapping("/admins/{id}/logout")
    public ForceLogoutResponse forceLogoutAdmin(@PathVariable("id") Long id) {
        // 서비스가 자기 자신인지 확인하고 막는다. 여기서는 대상 번호만 넘긴다
        return forceLogoutService.logoutAdmin(id);
    }
}
