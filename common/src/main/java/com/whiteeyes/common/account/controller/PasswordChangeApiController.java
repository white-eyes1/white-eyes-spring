package com.whiteeyes.common.account.controller;

import com.whiteeyes.common.account.dto.PasswordChangeRequest;
import com.whiteeyes.common.account.service.PasswordChangeService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 비밀번호 변경 API(FNC-C-007). 두 앱이 같은 경로로 등록한다: 사용자 앱 /api/v1/me/password, 관리자 앱 /admin/api/v1/me/password.
 * must_change_password=1인 계정도 부를 수 있다(PasswordChangeRequiredFilter가 이 경로를 막지 않음).
 */
@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class PasswordChangeApiController {

    private final PasswordChangeService passwordChangeService;
    private final SecurityContextRepository securityContextRepository = new HttpSessionSecurityContextRepository();

    public PasswordChangeApiController(PasswordChangeService passwordChangeService) {
        this.passwordChangeService = passwordChangeService;
    }

    @PatchMapping("/api/v1/me/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changePassword(
            @Valid @RequestBody PasswordChangeRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {
        passwordChangeService.change(request, httpRequest.getSession().getId());
        // 서비스가 현재 스레드의 로그인 정보를 새로 만들어 넣었으므로 세션에도 다시 저장한다(Spring Security는 자동 저장하지 않음)
        securityContextRepository.saveContext(SecurityContextHolder.getContext(), httpRequest, httpResponse);
    }
}
