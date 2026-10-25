package com.whiteeyes.user.account.controller;

import com.whiteeyes.user.account.dto.WithdrawRequest;
import com.whiteeyes.user.account.service.WithdrawService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 회원 탈퇴 API: DELETE /api/v1/me(FNC-U-004, 사용자 앱만). 성공하면 204이고 세션이 모두 지워진다. */
@RestController
public class WithdrawApiController {

    private final WithdrawService withdrawService;

    public WithdrawApiController(WithdrawService withdrawService) {
        this.withdrawService = withdrawService;
    }

    @DeleteMapping("/api/v1/me")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void withdraw(@Valid @RequestBody WithdrawRequest request, HttpServletRequest httpRequest) {
        HttpSession session = httpRequest.getSession();
        withdrawService.withdraw(request.password(), session.getId());
        // 지금 세션도 지운다. 세션 표에서 지우고 로그인 정보도 비워 같은 요청 안에서 다시 저장되지 않게 한다.
        // 처리 순서 4(로그인 화면으로 이동)는 화면이 '탈퇴되었습니다'를 보인 뒤 한다
        SecurityContextHolder.clearContext();
        session.invalidate();
    }
}
