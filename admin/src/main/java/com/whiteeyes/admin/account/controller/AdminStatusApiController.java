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

    private final AdminStatusService adminStatusService;

    @PostMapping("/{id}/disable")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void disable(@PathVariable("id") Long id) {
        adminStatusService.disable(id);
    }

    @PostMapping("/{id}/enable")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void enable(@PathVariable("id") Long id) {
        adminStatusService.enable(id);
    }
}
