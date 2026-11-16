package com.whiteeyes.admin.account.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.whiteeyes.admin.account.dto.AdminCreateRequest;
import com.whiteeyes.admin.account.dto.AdminCreateResponse;
import com.whiteeyes.admin.account.dto.AdminListItem;
import com.whiteeyes.admin.account.dto.AdminSearchRequest;
import com.whiteeyes.admin.account.service.AdminCreateService;
import com.whiteeyes.admin.account.service.AdminListService;
import com.whiteeyes.common.web.PageResponse;

// 최고 관리자만 부를 수 있다. 권한은 AdminSecurityConfig 권한 표(/api/v1/admins/**)가 막고, 관리자는 403이다
/** 관리자 목록·계정 생성 API: GET·POST /api/v1/admins(FNC-A-003·009). */
@RestController
@RequestMapping("/api/v1/admins")
@RequiredArgsConstructor
public class AdminApiController {

    // 컨트롤러는 요청을 받아 서비스에 넘기고 응답을 돌려주는 일만 한다. 업무 규칙은 서비스에 있다(생성자 주입)
    private final AdminListService adminListService;
    private final AdminCreateService adminCreateService;

    /** 관리자 목록 조회(FNC-A-003). 검색어·상태·활동 여부·정렬·페이지 조건을 쿼리 문자열로 받는다. */
    @GetMapping
    public PageResponse<AdminListItem> list(@Valid @ModelAttribute AdminSearchRequest request) {
        // @Valid 검사를 통과한 조건만 서비스로 넘긴다. 형식이 틀리면 여기까지 오지 않고 400으로 응답한다
        return adminListService.list(request);
    }

    /** 관리자 계정 생성(FNC-A-009). 임시 비밀번호는 이 응답에서 한 번만 보인다. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AdminCreateResponse create(@Valid @RequestBody AdminCreateRequest request) {
        // 아이디 중복 확인·임시 비밀번호 발급·저장·작업 기록은 서비스가 한 트랜잭션으로 처리한다
        return adminCreateService.create(request);
    }
}
