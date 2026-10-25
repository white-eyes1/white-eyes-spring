package com.whiteeyes.admin.account.controller;

import com.whiteeyes.admin.account.service.AdminStatusService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

// 최고 관리자만 부를 수 있다. 권한은 AdminSecurityConfig 권한 표(/api/v1/admins/**)가 막고, 관리자는 403이다
/**
 * 관리자 비활성화·활성화 API: POST /api/v1/admins/{id}/disable, /enable(FNC-A-011).
 * 세션 저장소가 웹 앱에만 있어 웹 서버 없이 띄우는 1회 작업(--job=…)에서는 등록하지 않는다.
 */
@RestController
@RequestMapping("/api/v1/admins")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
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
