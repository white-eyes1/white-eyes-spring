package com.whiteeyes.admin.account.controller;

import com.whiteeyes.admin.account.dto.UserListItem;
import com.whiteeyes.admin.account.dto.UserSearchRequest;
import com.whiteeyes.admin.account.service.UserApproveService;
import com.whiteeyes.admin.account.service.UserListService;
import com.whiteeyes.common.web.PageResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 사용자 관리 API: 목록 GET /api/v1/users(FNC-A-002), 승인 POST /api/v1/users/{id}/approve(FNC-A-006). */
@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserApiController {

    private final UserListService userListService;
    private final UserApproveService userApproveService;

    @GetMapping
    public PageResponse<UserListItem> list(@Valid @ModelAttribute UserSearchRequest request) {
        return userListService.list(request);
    }

    @PostMapping("/{id}/approve")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void approve(@PathVariable Long id) {
        userApproveService.approve(id);
    }
}
