package com.whiteeyes.common.security;

import com.whiteeyes.common.exception.BusinessException;
import com.whiteeyes.common.exception.ErrorCode;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/** 세션의 로그인 정보에서 지금 요청을 보낸 계정을 꺼낸다(FNC-C-026 처리 순서 2). */
@Component
public class LoginAccountResolver {

    /** 로그인하지 않았거나 계정 번호가 없는 옛 세션이면 401 UNAUTHORIZED로 거부한다. */
    public LoginAccount require() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null
                && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof AccountPrincipal principal
                && principal.getAccountId() != null) {
            return new LoginAccount(principal.getAccountId(), principal.getUsername());
        }
        throw new BusinessException(ErrorCode.UNAUTHORIZED);
    }
}
