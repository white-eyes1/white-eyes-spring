package com.whiteeyes.common.account.service;

import com.whiteeyes.common.account.dto.PasswordChangeRequest;
import com.whiteeyes.common.exception.BusinessException;
import com.whiteeyes.common.exception.ErrorCode;
import com.whiteeyes.common.security.AccountPrincipal;
import com.whiteeyes.common.security.LoginAccount;
import com.whiteeyes.common.security.LoginAccountResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 비밀번호 변경(FNC-C-007). 현재 비밀번호를 확인하고 새 비밀번호로 바꾼 뒤, 이 계정의 다른 세션을 지우고
 * 현재 세션의 로그인 정보에서 must_change_password를 끈다.
 *
 * 세션 저장소는 웹 앱에만 있으므로 웹 서버 없이 띄우는 1회 작업(--job=…)에서는 등록하지 않는다.
 */
@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class PasswordChangeService {

    private static final Logger log = LoggerFactory.getLogger(PasswordChangeService.class);

    private final PasswordEncoder passwordEncoder;
    private final AccountPasswordStore accountPasswordStore;
    private final FindByIndexNameSessionRepository<? extends Session> sessionRepository;
    private final LoginAccountResolver loginAccountResolver;

    public PasswordChangeService(
            PasswordEncoder passwordEncoder,
            AccountPasswordStore accountPasswordStore,
            FindByIndexNameSessionRepository<? extends Session> sessionRepository,
            LoginAccountResolver loginAccountResolver) {
        this.passwordEncoder = passwordEncoder;
        this.accountPasswordStore = accountPasswordStore;
        this.sessionRepository = sessionRepository;
        this.loginAccountResolver = loginAccountResolver;
    }

    /**
     * 로그인한 계정의 비밀번호를 바꾼다. 현재 스레드의 로그인 정보도 새로 만들어 넣으므로,
     * 호출한 쪽은 {@code SecurityContextHolder}의 값을 세션에 다시 저장해야 한다.
     *
     * @param currentSessionId 지금 요청의 세션 번호. 이 세션만 남기고 같은 계정의 나머지 세션을 지운다
     * @throws BusinessException 로그인하지 않았으면 401, 현재 비밀번호가 틀리면 400 WRONG_PASSWORD
     */
    @Transactional
    public void change(PasswordChangeRequest request, String currentSessionId) {
        LoginAccount account = loginAccountResolver.require();
        String storedPassword = accountPasswordStore
                .findEncodedPassword(account.accountId())
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
        if (!passwordEncoder.matches(request.currentPassword(), storedPassword)) {
            throw new BusinessException(ErrorCode.WRONG_PASSWORD);
        }

        accountPasswordStore.changePassword(account.accountId(), passwordEncoder.encode(request.newPassword()));
        int ended = deleteOtherSessions(account.loginId(), currentSessionId);
        refreshLoginInfo();
        log.info("비밀번호 변경 accountId={} 끊은 세션={}", account.accountId(), ended);
    }

    // 세션 주인 이름(PRINCIPAL_NAME)은 그 앱 계정 테이블의 login_id다(conventions.md 세션·로그인)
    private int deleteOtherSessions(String loginId, String currentSessionId) {
        int deleted = 0;
        for (String sessionId : sessionRepository.findByPrincipalName(loginId).keySet()) {
            if (!sessionId.equals(currentSessionId)) {
                sessionRepository.deleteById(sessionId);
                deleted++;
            }
        }
        return deleted;
    }

    private void refreshLoginInfo() {
        Authentication current = SecurityContextHolder.getContext().getAuthentication();
        AccountPrincipal refreshed = ((AccountPrincipal) current.getPrincipal()).passwordChanged();
        SecurityContextHolder.getContext()
                .setAuthentication(
                        UsernamePasswordAuthenticationToken.authenticated(refreshed, null, current.getAuthorities()));
    }
}
