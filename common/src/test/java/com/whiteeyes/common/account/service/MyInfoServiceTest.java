package com.whiteeyes.common.account.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
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
import com.whiteeyes.common.history.entity.ActorType;
import com.whiteeyes.common.history.entity.WorkAction;
import com.whiteeyes.common.history.entity.WorkTargetType;
import com.whiteeyes.common.history.service.WorkActorTypeProvider;
import com.whiteeyes.common.history.service.WorkRecorder;
import com.whiteeyes.common.security.AccountPrincipal;
import com.whiteeyes.common.security.LoginAccount;
import com.whiteeyes.common.security.LoginAccountResolver;
import java.util.Collections;
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

/**
 * 내 정보 조회·수정의 처리 순서(FNC-C-006). 계정 저장소는 가짜로 두고 "보낸 필드만 수정"과 비우기 규칙을 확인한다.
 */
class MyInfoServiceTest {

    private static final long ACCOUNT_ID = 7L;

    private final AccountProfileStore store = mock(AccountProfileStore.class);
    private final WorkRecorder workRecorder = mock(WorkRecorder.class);
    private final WorkActorTypeProvider actorTypeProvider = mock(WorkActorTypeProvider.class);
    private MyInfoService service;

    @BeforeEach
    void setUp() {
        when(actorTypeProvider.actorType()).thenReturn(ActorType.USER);
        service = new MyInfoService(new LoginAccountResolver(), store, workRecorder, actorTypeProvider);
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

    @SuppressWarnings("unchecked")
    private Map<String, Object> recordedDetail(WorkTargetType targetType) {
        ArgumentCaptor<Map<String, ?>> detail = ArgumentCaptor.forClass(Map.class);
        verify(workRecorder)
                .record(
                        any(LoginAccount.class),
                        eq(WorkAction.ACCOUNT_CHANGE),
                        eq(targetType),
                        eq(ACCOUNT_ID),
                        eq("hong01"),
                        detail.capture());
        return (Map<String, Object>) detail.getValue();
    }

    @Test
    @DisplayName("FNC-C-006·026 바뀐 필드만 이전·이후 값으로 ACCOUNT_CHANGE를 남긴다(대상은 본인 USER_ACCOUNT, 이름은 아이디)")
    void update_changed_recordsBeforeAfterOfChangedFieldsOnly() {
        when(store.findProfile(ACCOUNT_ID))
                .thenReturn(Optional.of(profile("홍길동", "a@b.com", "010-1", null)))
                .thenReturn(Optional.of(profile("김철수", "a@b.com", "010-2", null)));

        service.update(new MyInfoUpdateRequest("김철수", "a@b.com", "010-2"));

        Map<String, Object> detail = recordedDetail(WorkTargetType.USER_ACCOUNT);
        assertThat(detail.get("before")).isEqualTo(Map.of("name", "홍길동", "phone", "010-1"));
        assertThat(detail.get("after")).isEqualTo(Map.of("name", "김철수", "phone", "010-2"));
    }

    @Test
    @DisplayName("FNC-C-006·026 메일을 비우면 이후 값은 null로 남는다")
    void update_clearedField_recordsNullAsAfter() {
        when(store.findProfile(ACCOUNT_ID)).thenReturn(Optional.of(profile("홍길동", "a@b.com", "010-1", null)));

        service.update(new MyInfoUpdateRequest(null, "", null));

        Map<String, Object> detail = recordedDetail(WorkTargetType.USER_ACCOUNT);
        assertThat(detail.get("before")).isEqualTo(Map.of("email", "a@b.com"));
        assertThat(detail.get("after")).isEqualTo(Collections.singletonMap("email", null));
    }

    @Test
    @DisplayName("FNC-C-006·026 관리자 앱이면 대상은 ADMIN_ACCOUNT다")
    void update_adminApp_targetsAdminAccount() {
        when(actorTypeProvider.actorType()).thenReturn(ActorType.ADMIN);
        when(store.findProfile(ACCOUNT_ID)).thenReturn(Optional.of(profile("홍길동", "a@b.com", "010-1", "ADMIN")));

        service.update(new MyInfoUpdateRequest("김철수", null, null));

        recordedDetail(WorkTargetType.ADMIN_ACCOUNT);
    }

    @Test
    @DisplayName("FNC-C-006·026 바뀐 값이 하나도 없으면 기록하지 않는다(같은 값을 보내거나 빈 칸을 또 비우는 경우)")
    void update_nothingChanged_doesNotRecord() {
        when(store.findProfile(ACCOUNT_ID)).thenReturn(Optional.of(profile("홍길동", null, "010-1", null)));

        service.update(new MyInfoUpdateRequest("홍길동", "", "010-1"));
        service.update(new MyInfoUpdateRequest(null, null, null));

        verify(workRecorder, never()).record(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("FNC-C-026 기록 저장이 실패하면 예외를 그대로 던져 트랜잭션이 수정을 되돌리게 한다")
    void update_recordFails_propagates() {
        when(store.findProfile(ACCOUNT_ID)).thenReturn(Optional.of(profile("홍길동", "a@b.com", "010-1", null)));
        org.mockito.Mockito.doThrow(new IllegalStateException("record failed"))
                .when(workRecorder)
                .record(any(), any(), any(), any(), any(), any());

        assertThatThrownBy(() -> service.update(new MyInfoUpdateRequest("김철수", null, null)))
                .isInstanceOf(IllegalStateException.class);
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
