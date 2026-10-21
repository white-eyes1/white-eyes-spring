package com.whiteeyes.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.whiteeyes.common.exception.BusinessException;
import com.whiteeyes.common.exception.ErrorCode;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/** 작업 기록의 행동한 계정을 로그인 정보에서 꺼내는 규칙(FNC-C-026 처리 순서 2). */
class LoginAccountResolverTest {

    private final LoginAccountResolver resolver = new LoginAccountResolver();

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("FNC-C-026 로그인한 계정이면 계정 번호와 아이디를 돌려준다")
    void require_loggedIn_returnsAccount() {
        SecurityContextHolder.getContext().setAuthentication(authenticated(principal(7L)));

        LoginAccount account = resolver.require();

        assertThat(account.accountId()).isEqualTo(7L);
        assertThat(account.loginId()).isEqualTo("hong01");
    }

    @Test
    @DisplayName("FNC-C-026 로그인 정보가 없으면 401 UNAUTHORIZED")
    void require_noAuthentication_unauthorized() {
        assertUnauthorized();
    }

    @Test
    @DisplayName("FNC-C-026 익명 사용자이면 401 UNAUTHORIZED")
    void require_anonymous_unauthorized() {
        SecurityContextHolder.getContext()
                .setAuthentication(new AnonymousAuthenticationToken(
                        "key", "anonymousUser", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));

        assertUnauthorized();
    }

    @Test
    @DisplayName("FNC-C-026 계정 번호가 없는 옛 세션이면 401 UNAUTHORIZED")
    void require_principalWithoutAccountId_unauthorized() {
        SecurityContextHolder.getContext().setAuthentication(authenticated(principal(null)));

        assertUnauthorized();
    }

    private void assertUnauthorized() {
        assertThatThrownBy(resolver::require)
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.UNAUTHORIZED));
    }

    private AccountPrincipal principal(Long accountId) {
        return new AccountPrincipal(
                accountId, "hong01", "encoded", true, false, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    }

    private UsernamePasswordAuthenticationToken authenticated(AccountPrincipal principal) {
        return UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities());
    }
}
