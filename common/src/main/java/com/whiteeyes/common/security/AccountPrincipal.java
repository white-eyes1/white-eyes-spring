package com.whiteeyes.common.security;

import java.util.Collection;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.User;

/**
 * 로그인한 계정 정보(FNC-C-001). 로그인 뒤 어디로 보낼지 정하려고 must_change_password를, 작업 기록(FNC-C-026)이
 * 행동한 계정을 채우려고 계정 번호를 같이 들고 있다.
 * 세션에 저장되므로 비밀번호를 지우는 일은 Spring Security가 인증이 끝난 뒤 맡는다.
 */
// Spring Security의 User(아이디·비밀번호·권한을 가진 로그인 주체)를 확장해 계정 번호와 비밀번호 변경 강제 여부를 더 들고 있게 한 클래스
public class AccountPrincipal extends User {

    // 주체는 세션에 직렬화되어 저장되므로 직렬화 버전을 고정한다
    private static final long serialVersionUID = 1L;

    // 계정 테이블의 기본키. 작업 기록에서 "누가 했는지"를 채울 때 쓴다
    private final Long accountId;
    // 로그인 뒤 비밀번호 변경 화면으로 보낼지 가르는 값
    private final boolean mustChangePassword;

    public AccountPrincipal(
            Long accountId,
            String loginId,
            String encodedPassword,
            boolean enabled,
            boolean mustChangePassword,
            Collection<? extends GrantedAuthority> authorities) {
        // 사용 가능 여부(enabled)만 계정 상태를 따르고, 만료·잠금 개념은 쓰지 않아 true로 고정한다. 인자는 (아이디, 비밀번호 해시, 사용 가능, 계정 만료 안 됨, 자격 만료 안 됨,
        // 잠기지 않음, 권한 목록) 순서다
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

    /**
     * 비밀번호를 바꾼 뒤 세션에 다시 넣을 로그인 정보(FNC-C-007). must_change_password만 끄고 나머지는 그대로다.
     * 새 비밀번호 해시는 세션에 남기지 않으려고 비워 둔다.
     */
    public AccountPrincipal passwordChanged() {
        // 비밀번호는 빈 문자열로 바꾸고 변경 강제는 false로 한 새 객체를 만든다(주체는 불변이라 기존 객체를 고치지 않는다)
        return new AccountPrincipal(accountId, getUsername(), "", isEnabled(), false, getAuthorities());
    }
}
