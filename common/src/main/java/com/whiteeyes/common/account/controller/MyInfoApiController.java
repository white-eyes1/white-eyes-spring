package com.whiteeyes.common.account.controller;

import com.whiteeyes.common.account.dto.MyInfoResponse;
import com.whiteeyes.common.account.dto.MyInfoUpdateRequest;
import com.whiteeyes.common.account.service.MyInfoService;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 내 정보 조회·수정 API(FNC-C-006). 두 앱이 같은 경로로 등록한다: 사용자 앱 /api/v1/me, 관리자 앱 /admin/api/v1/me.
 */
@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class MyInfoApiController {

    private final MyInfoService myInfoService;

    public MyInfoApiController(MyInfoService myInfoService) {
        this.myInfoService = myInfoService;
    }

    @GetMapping("/api/v1/me")
    public MyInfoResponse getMyInfo() {
        return myInfoService.get();
    }

    @PatchMapping("/api/v1/me")
    public MyInfoResponse updateMyInfo(@Valid @RequestBody MyInfoUpdateRequest request) {
        return myInfoService.update(request);
    }
}
