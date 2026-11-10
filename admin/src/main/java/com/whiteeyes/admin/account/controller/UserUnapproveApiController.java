package com.whiteeyes.admin.account.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.whiteeyes.admin.account.service.UserUnapproveService;

/**
 * 사용자 승인 취소 API: POST /api/v1/users/{id}/unapprove(FNC-A-007).
 */
@RestController
@RequestMapping("/api/v1/users")
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
