package com.whiteeyes.common.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import com.whiteeyes.common.exception.BusinessException;
import com.whiteeyes.common.exception.ErrorCode;

/** 세션의 로그인 정보에서 지금 요청을 보낸 계정을 꺼낸다(FNC-C-026 처리 순서 2). */
@Component
public class LoginAccountResolver {

    /** 로그인하지 않았거나 계정 번호가 없는 옛 세션이면 401 UNAUTHORIZED로 거부한다. */
    public LoginAccount require() {
        // 이 요청 스레드에 걸려 있는 인증 정보. 로그인하지 않았으면 null이거나 익명 인증이다
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        // 인증됐고, 주체가 우리가 만든 AccountPrincipal이며, 계정 번호가 있어야 통과한다. 익명 사용자나 계정 번호가 없는 옛 세션은 여기서 걸러진다
        if (authentication != null
                && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof AccountPrincipal principal
                && principal.getAccountId() != null) {
            return new LoginAccount(principal.getAccountId(), principal.getUsername());
        }
        // 위 조건을 모두 만족하지 못하면 로그인이 필요한 상태로 본다
        throw new BusinessException(ErrorCode.UNAUTHORIZED);
    }
}
