package com.whiteeyes.common.account.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.whiteeyes.common.account.dto.PasswordChangeRequest;
import com.whiteeyes.common.account.service.PasswordChangeService;

/**
 * 비밀번호 변경 API(FNC-C-007). 두 앱이 같은 경로로 등록한다: 사용자 앱 /api/v1/me/password, 관리자 앱 /admin/api/v1/me/password.
 * must_change_password=1인 계정도 부를 수 있다(PasswordChangeRequiredFilter가 이 경로를 막지 않음).
 */
@RestController
@RequiredArgsConstructor
public class PasswordChangeApiController {

    // 현재 비밀번호 확인·변경·다른 세션 정리를 맡는 서비스
    private final PasswordChangeService passwordChangeService;
    // 변경 후 새로 만든 로그인 정보를 HTTP 세션에 저장하는 도구. 필터 밖(컨트롤러)에서 바꾼 인증 정보는 자동 저장되지 않아 직접 저장해야 한다
    private final SecurityContextRepository securityContextRepository = new HttpSessionSecurityContextRepository();

    /**
     * 비밀번호를 바꾼다. 성공하면 본문 없이 204를 돌려준다.
     * 현재 요청의 세션은 남기고 같은 계정의 다른 세션은 서비스가 끊는다.
     */
    @PatchMapping("/api/v1/me/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changePassword(
            @Valid @RequestBody PasswordChangeRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {
        // 현재 세션 번호를 넘겨, 다른 곳에서 로그인한 같은 계정의 세션만 끊게 한다
        passwordChangeService.change(request, httpRequest.getSession().getId());
        // 서비스가 현재 스레드의 로그인 정보를 새로 만들어 넣었으므로 세션에도 다시 저장한다(Spring Security는 자동 저장하지 않음)
        securityContextRepository.saveContext(SecurityContextHolder.getContext(), httpRequest, httpResponse);
    }
}
