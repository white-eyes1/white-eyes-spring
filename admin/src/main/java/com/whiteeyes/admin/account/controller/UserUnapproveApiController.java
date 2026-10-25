package com.whiteeyes.admin.account.controller;

import com.whiteeyes.admin.account.service.UserUnapproveService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 사용자 승인 취소 API: POST /api/v1/users/{id}/unapprove(FNC-A-007).
 * 세션 저장소가 웹 앱에만 있어 웹 서버 없이 띄우는 1회 작업(--job=…)에서는 등록하지 않는다.
 */
@RestController
@RequestMapping("/api/v1/users")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequiredArgsConstructor
public class UserUnapproveApiController {

    private final UserUnapproveService userUnapproveService;

    /** ACTIVE → PENDING으로 되돌리고 세션을 모두 지운다. ACTIVE가 아니면 409, 없는 사용자는 404다. */
    @PostMapping("/{id}/unapprove")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unapprove(@PathVariable("id") Long id) {
        userUnapproveService.unapprove(id);
    }
}
