package com.whiteeyes.common.account.service;

import com.whiteeyes.common.account.dto.MyInfoResponse;
import com.whiteeyes.common.account.dto.MyInfoUpdateRequest;
import com.whiteeyes.common.exception.BusinessException;
import com.whiteeyes.common.exception.ErrorCode;
import com.whiteeyes.common.history.entity.ActorType;
import com.whiteeyes.common.history.entity.WorkAction;
import com.whiteeyes.common.history.entity.WorkTargetType;
import com.whiteeyes.common.history.service.WorkActorTypeProvider;
import com.whiteeyes.common.history.service.WorkRecorder;
import com.whiteeyes.common.security.LoginAccount;
import com.whiteeyes.common.security.LoginAccountResolver;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 내 정보 조회·수정(FNC-C-006). 로그인한 본인의 행만 읽고 쓴다. 아이디·역할·상태는 바꾸지 않는다.
 *
 * 수정하면 작업 기록(FNC-C-026, ACCOUNT_CHANGE)을 같은 트랜잭션으로 남긴다. 행동한 계정과 대상은 모두 본인이고,
 * detail은 바뀐 필드의 이전·이후 값이다. 바뀐 값이 없으면 기록하지 않는다.
 */
@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class MyInfoService {

    private static final Logger log = LoggerFactory.getLogger(MyInfoService.class);

    private final LoginAccountResolver loginAccountResolver;
    private final AccountProfileStore accountProfileStore;
    private final WorkRecorder workRecorder;
    private final WorkActorTypeProvider actorTypeProvider;

    public MyInfoService(
            LoginAccountResolver loginAccountResolver,
            AccountProfileStore accountProfileStore,
            WorkRecorder workRecorder,
            WorkActorTypeProvider actorTypeProvider) {
        this.loginAccountResolver = loginAccountResolver;
        this.accountProfileStore = accountProfileStore;
        this.workRecorder = workRecorder;
        this.actorTypeProvider = actorTypeProvider;
    }

    /** @throws BusinessException 로그인하지 않았으면 401 */
    @Transactional(readOnly = true)
    public MyInfoResponse get() {
        return toResponse(find(loginAccountResolver.require()));
    }

    /**
     * 보낸 필드만 고친다. 보내지 않은(null) 필드는 저장된 값을 그대로 두고,
     * 메일·연락처를 빈 문자열로 보내면 비운다(NULL 저장). 이름의 빈 문자열은 요청 검사에서 먼저 걸러진다.
     *
     * 저장한 뒤 바뀐 필드가 있으면 작업 기록을 남긴다. 기록이 실패하면 예외가 그대로 올라가 수정도 취소된다.
     *
     * @throws BusinessException 로그인하지 않았거나 계정 행이 없으면 401
     */
    @Transactional
    public MyInfoResponse update(MyInfoUpdateRequest request) {
        LoginAccount account = loginAccountResolver.require();
        AccountProfile current = find(account);

        String name = request.name() != null ? request.name() : current.name();
        String email = request.email() != null ? emptyToNull(request.email()) : current.email();
        String phone = request.phone() != null ? emptyToNull(request.phone()) : current.phone();
        accountProfileStore.updateProfile(account.accountId(), name, email, phone);
        // 처리 순서 4: 작업 기록(바뀐 필드의 이전·이후 값). 바뀐 값이 없으면 기록하지 않는다
        recordChange(account, current, name, email, phone);
        // 개인 정보(이름·메일·연락처)는 로그에 남기지 않는다(code-conventions 7-4)
        log.info("내 정보 수정 accountId={}", account.accountId());

        return toResponse(find(account));
    }

    private void recordChange(LoginAccount account, AccountProfile current, String name, String email, String phone) {
        Map<String, Object> before = new LinkedHashMap<>();
        Map<String, Object> after = new LinkedHashMap<>();
        collectChange("name", current.name(), name, before, after);
        collectChange("email", current.email(), email, before, after);
        collectChange("phone", current.phone(), phone, before, after);
        if (before.isEmpty()) {
            return;
        }
        // 대상은 본인 계정이다. 계정의 대상 이름은 아이디다(tables.md work_history.target_name)
        WorkTargetType targetType = actorTypeProvider.actorType() == ActorType.ADMIN
                ? WorkTargetType.ADMIN_ACCOUNT
                : WorkTargetType.USER_ACCOUNT;
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("before", before);
        detail.put("after", after);
        workRecorder.record(
                account, WorkAction.ACCOUNT_CHANGE, targetType, account.accountId(), account.loginId(), detail);
    }

    private void collectChange(
            String field, String was, String now, Map<String, Object> before, Map<String, Object> after) {
        if (!Objects.equals(was, now)) {
            before.put(field, was);
            after.put(field, now);
        }
    }

    private AccountProfile find(LoginAccount account) {
        return accountProfileStore
                .findProfile(account.accountId())
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
    }

    private String emptyToNull(String value) {
        return value.isEmpty() ? null : value;
    }

    private MyInfoResponse toResponse(AccountProfile profile) {
        return new MyInfoResponse(
                profile.id(),
                profile.loginId(),
                profile.name(),
                profile.email(),
                profile.phone(),
                profile.status(),
                profile.mustChangePassword(),
                profile.role());
    }
}
