package com.whiteeyes.common.account.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;

import com.whiteeyes.common.account.dto.MyInfoResponse;
import com.whiteeyes.common.account.dto.MyInfoUpdateRequest;
import com.whiteeyes.common.exception.BusinessException;
import com.whiteeyes.common.exception.ErrorCode;
import com.whiteeyes.common.security.AccountPrincipal;
import com.whiteeyes.common.security.LoginAccountResolver;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 내 정보 조회·수정의 처리 순서(FNC-C-006). 계정 저장소는 가짜로 두고 "보낸 필드만 수정"과 비우기 규칙을 확인한다.
 */
class MyInfoServiceTest {

    private static final long ACCOUNT_ID = 7L;

    private final AccountProfileStore store = mock(AccountProfileStore.class);
    private MyInfoService service;

    @BeforeEach
    void setUp() {
        service = new MyInfoService(new LoginAccountResolver(), store);
        AccountPrincipal principal = new AccountPrincipal(
                ACCOUNT_ID, "hong01", "", true, false, List.of(new SimpleGrantedAuthority("ROLE_USER")));
        SecurityContextHolder.getContext()
                .setAuthentication(
                        UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities()));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private AccountProfile profile(String name, String email, String phone, String role) {
        return new AccountProfile(ACCOUNT_ID, "hong01", name, email, phone, "ACTIVE", role, false);
    }

    @Test
    @DisplayName("FNC-C-006 조회하면 로그인한 계정의 정보를 돌려주고 사용자 계정에는 role이 없다")
    void get_returnsOwnProfile() {
        when(store.findProfile(ACCOUNT_ID)).thenReturn(Optional.of(profile("홍길동", "a@b.com", "010-1", null)));

        MyInfoResponse response = service.get();

        assertThat(response.id()).isEqualTo(ACCOUNT_ID);
        assertThat(response.loginId()).isEqualTo("hong01");
        assertThat(response.name()).isEqualTo("홍길동");
        assertThat(response.role()).isNull();
        assertThat(response.mustChangePassword()).isFalse();
    }

    @Test
    @DisplayName("FNC-C-006 보낸 필드만 고치고 보내지 않은 필드는 저장된 값을 그대로 쓴다")
    void update_onlySentFields() {
        when(store.findProfile(ACCOUNT_ID))
                .thenReturn(Optional.of(profile("홍길동", "a@b.com", "010-1", null)))
                .thenReturn(Optional.of(profile("김철수", "a@b.com", "010-1", null)));

        MyInfoResponse response = service.update(new MyInfoUpdateRequest("김철수", null, null));

        verify(store).updateProfile(ACCOUNT_ID, "김철수", "a@b.com", "010-1");
        assertThat(response.name()).isEqualTo("김철수");
    }

    @Test
    @DisplayName("FNC-C-006 메일·연락처를 빈 문자열로 보내면 비운다(NULL로 저장)")
    void update_emptyString_clearsOptionalFields() {
        when(store.findProfile(ACCOUNT_ID)).thenReturn(Optional.of(profile("홍길동", "a@b.com", "010-1", null)));

        service.update(new MyInfoUpdateRequest(null, "", ""));

        verify(store).updateProfile(ACCOUNT_ID, "홍길동", null, null);
    }

    @Test
    @DisplayName("FNC-C-006 저장된 계정이 없으면 401로 거부하고 저장하지 않는다")
    void update_missingAccount_unauthorized() {
        when(store.findProfile(ACCOUNT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(new MyInfoUpdateRequest("김철수", null, null)))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(ErrorCode.UNAUTHORIZED));
        verify(store, never()).updateProfile(anyLong(), any(), any(), any());
    }

    @Test
    @DisplayName("FNC-C-006 로그인하지 않았으면 401로 거부한다")
    void get_notLoggedIn_unauthorized() {
        SecurityContextHolder.clearContext();

        assertThatThrownBy(() -> service.get())
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(ErrorCode.UNAUTHORIZED));
    }
}
