package com.whiteeyes.common.account.service;

import com.whiteeyes.common.account.dto.MyInfoResponse;
import com.whiteeyes.common.account.dto.MyInfoUpdateRequest;
import com.whiteeyes.common.exception.BusinessException;
import com.whiteeyes.common.exception.ErrorCode;
import com.whiteeyes.common.security.LoginAccount;
import com.whiteeyes.common.security.LoginAccountResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 내 정보 조회·수정(FNC-C-006). 로그인한 본인의 행만 읽고 쓴다. 아이디·역할·상태는 바꾸지 않는다.
 *
 * 작업 기록(FNC-C-026)은 남기지 않는다: 명세가 본인 정보 수정을 기록 대상으로 정하지 않았다.
 */
@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class MyInfoService {

    private static final Logger log = LoggerFactory.getLogger(MyInfoService.class);

    private final LoginAccountResolver loginAccountResolver;
    private final AccountProfileStore accountProfileStore;

    public MyInfoService(LoginAccountResolver loginAccountResolver, AccountProfileStore accountProfileStore) {
        this.loginAccountResolver = loginAccountResolver;
        this.accountProfileStore = accountProfileStore;
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
        // 개인 정보(이름·메일·연락처)는 로그에 남기지 않는다(code-conventions 7-4)
        log.info("내 정보 수정 accountId={}", account.accountId());

        return toResponse(find(account));
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
