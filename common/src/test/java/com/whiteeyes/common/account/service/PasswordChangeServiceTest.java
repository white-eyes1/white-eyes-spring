package com.whiteeyes.common.account.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.whiteeyes.common.account.dto.PasswordChangeRequest;
import com.whiteeyes.common.exception.BusinessException;
import com.whiteeyes.common.exception.ErrorCode;
import com.whiteeyes.common.security.AccountPrincipal;
import com.whiteeyes.common.security.LoginAccountResolver;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;

/**
 * 비밀번호 변경의 처리 순서(FNC-C-007). 계정 저장소와 세션 저장소는 가짜로 두고 순서·호출만 확인한다.
 */
class PasswordChangeServiceTest {

    private static final long ACCOUNT_ID = 7L;
    private static final String LOGIN_ID = "admin01";
    private static final String CURRENT = "current-pass1!";
    private static final String NEW = "new-password2@";
    private static final String CURRENT_SESSION = "session-current";

    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder(4);
    private final AccountPasswordStore store = mock(AccountPasswordStore.class);

    @SuppressWarnings("unchecked")
    private final FindByIndexNameSessionRepository<Session> sessions = mock(FindByIndexNameSessionRepository.class);

    private PasswordChangeService service;

    @BeforeEach
    void setUp() {
        service = new PasswordChangeService(passwordEncoder, store, sessions, new LoginAccountResolver());
        AccountPrincipal principal = new AccountPrincipal(
                ACCOUNT_ID, LOGIN_ID, "", true, true, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        SecurityContextHolder.getContext()
                .setAuthentication(
                        UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities()));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private PasswordChangeRequest request(String current) {
        return new PasswordChangeRequest(current, NEW, NEW);
    }

    @Test
    @DisplayName("FNC-C-007 현재 비밀번호가 맞으면 새 비밀번호를 bcrypt로 저장한다")
    void change_correctCurrent_storesEncodedNewPassword() {
        when(store.findEncodedPassword(ACCOUNT_ID)).thenReturn(Optional.of(passwordEncoder.encode(CURRENT)));
        when(sessions.findByPrincipalName(LOGIN_ID)).thenReturn(Map.of());

        service.change(request(CURRENT), CURRENT_SESSION);

        ArgumentCaptor<String> saved = ArgumentCaptor.forClass(String.class);
        verify(store).changePassword(eq(ACCOUNT_ID), saved.capture());
        assertThat(saved.getValue()).isNotEqualTo(NEW);
        assertThat(passwordEncoder.matches(NEW, saved.getValue())).isTrue();
    }

    @Test
    @DisplayName("FNC-C-007 이 계정의 다른 세션은 모두 지우고 현재 세션은 남긴다")
    void change_deletesOtherSessionsOnly() {
        when(store.findEncodedPassword(ACCOUNT_ID)).thenReturn(Optional.of(passwordEncoder.encode(CURRENT)));
        Session placeholder = mock(Session.class);
        when(sessions.findByPrincipalName(LOGIN_ID))
                .thenReturn(Map.of(CURRENT_SESSION, placeholder, "other-1", placeholder, "other-2", placeholder));

        service.change(request(CURRENT), CURRENT_SESSION);

        verify(sessions).deleteById("other-1");
        verify(sessions).deleteById("other-2");
        verify(sessions, never()).deleteById(CURRENT_SESSION);
    }

    @Test
    @DisplayName("FNC-C-007 성공하면 현재 로그인 정보의 must_change_password가 꺼진다(#4에서 넘겨받은 일)")
    void change_success_refreshesPrincipalInContext() {
        when(store.findEncodedPassword(ACCOUNT_ID)).thenReturn(Optional.of(passwordEncoder.encode(CURRENT)));
        when(sessions.findByPrincipalName(LOGIN_ID)).thenReturn(Map.of());

        service.change(request(CURRENT), CURRENT_SESSION);

        var authentication = SecurityContextHolder.getContext().getAuthentication();
        assertThat(authentication.getPrincipal()).isInstanceOfSatisfying(AccountPrincipal.class, principal -> {
            assertThat(principal.isMustChangePassword()).isFalse();
            assertThat(principal.getAccountId()).isEqualTo(ACCOUNT_ID);
            assertThat(principal.getUsername()).isEqualTo(LOGIN_ID);
        });
        assertThat(authentication.getAuthorities()).extracting(Object::toString).containsExactly("ROLE_ADMIN");
        assertThat(authentication.isAuthenticated()).isTrue();
    }

    @Test
    @DisplayName("FNC-C-007 현재 비밀번호가 틀리면 400 WRONG_PASSWORD이고 아무것도 바꾸지 않는다")
    void change_wrongCurrent_rejectedWithoutChanges() {
        when(store.findEncodedPassword(ACCOUNT_ID)).thenReturn(Optional.of(passwordEncoder.encode(CURRENT)));

        assertThatThrownBy(() -> service.change(request("wrong-pass9!"), CURRENT_SESSION))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.WRONG_PASSWORD));

        verify(store, never()).changePassword(anyLong(), anyString());
        verify(sessions, never()).deleteById(anyString());
        AccountPrincipal principal = (AccountPrincipal)
                SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        assertThat(principal.isMustChangePassword()).isTrue();
    }

    @Test
    @DisplayName("FNC-C-007 계정을 찾을 수 없으면 401이다")
    void change_accountMissing_unauthorized() {
        when(store.findEncodedPassword(ACCOUNT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.change(request(CURRENT), CURRENT_SESSION))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.UNAUTHORIZED));
    }

    @Test
    @DisplayName("FNC-C-007 로그인하지 않은 상태로 부르면 401이다")
    void change_notLoggedIn_unauthorized() {
        SecurityContextHolder.clearContext();

        assertThatThrownBy(() -> service.change(request(CURRENT), CURRENT_SESSION))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.UNAUTHORIZED));
    }
}
