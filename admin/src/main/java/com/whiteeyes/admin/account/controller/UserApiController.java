package com.whiteeyes.admin.account.controller;

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

import com.whiteeyes.admin.account.dto.UserListItem;
import com.whiteeyes.admin.account.dto.UserSearchRequest;
import com.whiteeyes.admin.account.service.UserApproveService;
import com.whiteeyes.admin.account.service.UserListService;
import com.whiteeyes.common.web.PageResponse;

/** 사용자 관리 API: 목록 GET /api/v1/users(FNC-A-002), 승인 POST /api/v1/users/{id}/approve(FNC-A-006). */
@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserApiController {

    // 목록 조회와 승인은 각각 다른 서비스가 맡는다(한 서비스에 몰아 두지 않음)
    private final UserListService userListService;
    private final UserApproveService userApproveService;

    /** 사용자 목록 조회. 검색어·상태·가입일·활동 여부·정렬·페이지 조건을 쿼리 문자열로 받는다. */
    @GetMapping
    public PageResponse<UserListItem> list(@Valid @ModelAttribute UserSearchRequest request) {
        // @Valid로 형식·가입일 범위를 검사한 조건만 서비스로 넘긴다
        return userListService.list(request);
    }

    /** 미승인 사용자를 승인한다(PENDING→ACTIVE). 성공하면 204다. 상태가 맞지 않으면 서비스가 409를 낸다. */
    @PostMapping("/{id}/approve")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void approve(@PathVariable Long id) {
        // 상태 확인·변경·작업 기록은 서비스의 한 트랜잭션에서 처리한다
        userApproveService.approve(id);
    }
}
