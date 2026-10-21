package com.whiteeyes.common.security;

import java.util.Collection;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.User;

/**
 * 로그인한 계정 정보(FNC-C-001). 로그인 뒤 어디로 보낼지 정하려고 must_change_password를, 작업 기록(FNC-C-026)이
 * 행동한 계정을 채우려고 계정 번호를 같이 들고 있다.
 * 세션에 저장되므로 비밀번호를 지우는 일은 Spring Security가 인증이 끝난 뒤 맡는다.
 */
public class AccountPrincipal extends User {

    private static final long serialVersionUID = 1L;

    private final Long accountId;
    private final boolean mustChangePassword;

    public AccountPrincipal(
            Long accountId,
            String loginId,
            String encodedPassword,
            boolean enabled,
            boolean mustChangePassword,
            Collection<? extends GrantedAuthority> authorities) {
        super(loginId, encodedPassword, enabled, true, true, true, authorities);
        this.accountId = accountId;
        this.mustChangePassword = mustChangePassword;
    }

    public Long getAccountId() {
        return accountId;
    }

    public boolean isMustChangePassword() {
        return mustChangePassword;
    }
}
