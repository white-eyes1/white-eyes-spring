package com.whiteeyes.user.account.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.whiteeyes.user.account.dto.WithdrawRequest;
import com.whiteeyes.user.account.service.WithdrawService;

/** 회원 탈퇴 API: DELETE /api/v1/me(FNC-U-004, 사용자 앱만). 성공하면 204이고 세션이 모두 지워진다. */
@RestController
@RequiredArgsConstructor
public class WithdrawApiController {

    private final WithdrawService withdrawService;

    /**
     * 회원 탈퇴(FNC-U-004). 본인 확인용 비밀번호를 받아 계정을 탈퇴 상태로 바꾸고 세션을 모두 끝낸다.
     *
     * @param request 현재 비밀번호
     * @param httpRequest 지금 요청의 세션을 얻기 위해 받는다
     */
    @DeleteMapping("/api/v1/me")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void withdraw(@Valid @RequestBody WithdrawRequest request, HttpServletRequest httpRequest) {
        // 지금 요청의 세션. 서비스가 "다른 세션"만 지우고 지금 세션은 아래에서 직접 끝내려고 번호를 넘긴다.
        HttpSession session = httpRequest.getSession();
        // 비밀번호 확인 → 탈퇴 처리 → 다른 세션 삭제까지 한 트랜잭션으로 서비스가 처리한다. 실패하면 예외로 끝나 아래 줄은 실행되지 않는다.
        withdrawService.withdraw(request.password(), session.getId());
        // 지금 세션도 지운다. 세션 표에서 지우고 로그인 정보도 비워 같은 요청 안에서 다시 저장되지 않게 한다.
        // 처리 순서 4(로그인 화면으로 이동)는 화면이 '탈퇴되었습니다'를 보인 뒤 한다
        SecurityContextHolder.clearContext();
        session.invalidate();
    }
}
