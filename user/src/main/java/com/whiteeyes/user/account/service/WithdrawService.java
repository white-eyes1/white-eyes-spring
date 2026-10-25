package com.whiteeyes.user.account.service;

import com.whiteeyes.common.account.entity.UserAccount;
import com.whiteeyes.common.account.repository.UserAccountRepository;
import com.whiteeyes.common.exception.BusinessException;
import com.whiteeyes.common.exception.ErrorCode;
import com.whiteeyes.common.security.LoginAccount;
import com.whiteeyes.common.security.LoginAccountResolver;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 회원 탈퇴(FNC-U-004). 비밀번호를 확인하고 계정을 WITHDRAWN으로 바꾼 뒤 이 계정의 세션을 지운다.
 * 행은 지우지 않는다(작업 기록 보존). 그래서 같은 아이디로 다시 가입할 수 없고, 로그인은 UserAccountDetailsService가 거부한다.
 */
@Service
public class WithdrawService {

    private static final Logger log = LoggerFactory.getLogger(WithdrawService.class);

    // DB에는 한국 시간(KST)으로 저장한다(conventions.md '시각 형식')
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final UserAccountRepository userAccountRepository;
    private final PasswordEncoder passwordEncoder;
    private final FindByIndexNameSessionRepository<? extends Session> sessionRepository;
    private final LoginAccountResolver loginAccountResolver;

    public WithdrawService(
            UserAccountRepository userAccountRepository,
            PasswordEncoder passwordEncoder,
            FindByIndexNameSessionRepository<? extends Session> sessionRepository,
            LoginAccountResolver loginAccountResolver) {
        this.userAccountRepository = userAccountRepository;
        this.passwordEncoder = passwordEncoder;
        this.sessionRepository = sessionRepository;
        this.loginAccountResolver = loginAccountResolver;
    }

    /**
     * 지금 요청의 세션은 남겨 두고 같은 계정의 다른 세션만 지운다. 지금 세션은 부르는 쪽이 무효화한다
     * (세션 표에서 직접 지우면 요청이 끝날 때 Spring Session이 다시 저장한다).
     *
     * @param currentSessionId 지금 요청의 세션 번호
     * @throws BusinessException 로그인하지 않았으면 401, 비밀번호가 틀리면 400 WRONG_PASSWORD
     */
    @Transactional
    public void withdraw(String password, String currentSessionId) {
        LoginAccount account = loginAccountResolver.require();
        UserAccount user = userAccountRepository
                .findByIdForUpdate(account.accountId())
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
        // 처리 순서 1: 비밀번호 확인
        if (!passwordEncoder.matches(password, user.getPassword())) {
            throw new BusinessException(ErrorCode.WRONG_PASSWORD);
        }
        // 처리 순서 2: status=WITHDRAWN, withdrawn_at=현재 시각
        user.withdraw(LocalDateTime.now(KST));
        // 처리 순서 3: 이 계정의 모든 세션 삭제(지금 세션은 컨트롤러가 무효화)
        int ended = 0;
        for (String sessionId : sessionRepository.findByPrincipalName(user.getLoginId()).keySet()) {
            if (!sessionId.equals(currentSessionId)) {
                sessionRepository.deleteById(sessionId);
                ended++;
            }
        }
        log.info("회원 탈퇴 accountId={} 끊은 다른 세션={}", account.accountId(), ended);
    }
}
