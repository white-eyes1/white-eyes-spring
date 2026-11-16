package com.whiteeyes.admin.account.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.whiteeyes.admin.account.dto.UserListItem;
import com.whiteeyes.admin.account.dto.UserUpdateRequest;
import com.whiteeyes.admin.account.service.UserUpdateService;

/** 사용자 정보 수정 API: PATCH /api/v1/users/{id}(FNC-A-004). 권한은 AdminSecurityConfig 권한 표(관리자 이상)다. */
@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserUpdateApiController {

    // 탈퇴 계정 거부·변경 값 계산·작업 기록은 서비스가 맡는다
    private final UserUpdateService userUpdateService;

    /** 이름·메일·연락처를 고치고 수정된 사용자를 돌려준다. 형식 오류는 400(fieldErrors), 없는 사용자는 404다. */
    @PatchMapping("/{id}")
    public UserListItem update(@PathVariable("id") Long id, @Valid @RequestBody UserUpdateRequest request) {
        // 경로의 id가 수정 대상, 본문이 바꿀 값이다. 보낸 필드만 반영된다
        return userUpdateService.update(id, request);
    }
}
