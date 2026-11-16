package com.whiteeyes.admin.account.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.whiteeyes.admin.account.service.AdminStatusService;

// 최고 관리자만 부를 수 있다. 권한은 AdminSecurityConfig 권한 표(/api/v1/admins/**)가 막고, 관리자는 403이다
/**
 * 관리자 비활성화·활성화 API: POST /api/v1/admins/{id}/disable, /enable(FNC-A-011).
 */
@RestController
@RequestMapping("/api/v1/admins")
@RequiredArgsConstructor
public class AdminStatusApiController {

    // 상태 변경 규칙(최고 관리자 제외, 현재 상태 확인, 세션 삭제)은 서비스가 맡는다
    private final AdminStatusService adminStatusService;

    /** 관리자를 비활성화한다. 성공하면 본문 없이 204를 돌려준다. */
    @PostMapping("/{id}/disable")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void disable(@PathVariable("id") Long id) {
        // 상태·세션 처리는 모두 서비스 안에서 끝나므로 컨트롤러는 반환값이 없다
        adminStatusService.disable(id);
    }

    /** 비활성화된 관리자를 다시 활성화한다. 성공하면 본문 없이 204를 돌려준다. */
    @PostMapping("/{id}/enable")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void enable(@PathVariable("id") Long id) {
        adminStatusService.enable(id);
    }
}
