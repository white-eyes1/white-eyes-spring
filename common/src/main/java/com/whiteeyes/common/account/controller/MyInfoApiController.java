package com.whiteeyes.common.account.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.whiteeyes.common.account.dto.MyInfoResponse;
import com.whiteeyes.common.account.dto.MyInfoUpdateRequest;
import com.whiteeyes.common.account.service.MyInfoService;

/**
 * 내 정보 조회·수정 API(FNC-C-006). 두 앱이 같은 경로로 등록한다: 사용자 앱 /api/v1/me, 관리자 앱 /admin/api/v1/me.
 */
@RestController
@RequiredArgsConstructor
public class MyInfoApiController {

    // 조회·수정 규칙은 모두 서비스에 있고 컨트롤러는 요청을 넘기고 결과를 돌려주기만 한다
    private final MyInfoService myInfoService;

    /** 로그인한 본인의 내 정보를 돌려준다. 누구의 정보인지는 요청 값이 아니라 로그인 세션에서 정해진다. */
    @GetMapping("/api/v1/me")
    public MyInfoResponse getMyInfo() {
        // 대상 계정은 서비스가 로그인 정보에서 꺼내므로 컨트롤러는 계정 번호를 넘기지 않는다
        return myInfoService.get();
    }

    /** 내 정보를 일부만 고치고(PATCH) 고친 결과를 다시 돌려준다. 본문은 @Valid로 먼저 형식 검사를 한다. */
    @PatchMapping("/api/v1/me")
    public MyInfoResponse updateMyInfo(@Valid @RequestBody MyInfoUpdateRequest request) {
        // 규칙 검사를 통과한 요청만 여기까지 온다
        return myInfoService.update(request);
    }
}
