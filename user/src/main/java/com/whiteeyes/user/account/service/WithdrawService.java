package com.whiteeyes.user.account.service;

import java.time.LocalDateTime;
import java.time.ZoneId;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.whiteeyes.common.account.entity.UserAccount;
import com.whiteeyes.common.account.repository.UserAccountRepository;
import com.whiteeyes.common.exception.BusinessException;
import com.whiteeyes.common.exception.ErrorCode;
import com.whiteeyes.common.security.LoginAccount;
import com.whiteeyes.common.security.LoginAccountResolver;

/**
 * 회원 탈퇴(FNC-U-004). 비밀번호를 확인하고 계정을 WITHDRAWN으로 바꾼 뒤 이 계정의 세션을 지운다.
 * 행은 지우지 않는다(작업 기록 보존). 그래서 같은 아이디로 다시 가입할 수 없고, 로그인은 UserAccountDetailsService가 거부한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WithdrawService {

    // DB에는 한국 시간(KST)으로 저장한다(conventions.md '시각 형식')
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    // 계정 조회·잠금, 비밀번호 비교, 세션 정리에 필요한 협력 객체들.
    private final UserAccountRepository userAccountRepository;
    private final PasswordEncoder passwordEncoder;
    // 로그인 아이디(principal name)로 그 계정의 모든 세션을 찾는 Spring Session 저장소.
    private final FindByIndexNameSessionRepository<? extends Session> sessionRepository;
    private final LoginAccountResolver loginAccountResolver;

    /**
     * 지금 요청의 세션은 남겨 두고 같은 계정의 다른 세션만 지운다. 지금 세션은 부르는 쪽이 무효화한다
     * (세션 표에서 직접 지우면 요청이 끝날 때 Spring Session이 다시 저장한다).
     *
     * @param currentSessionId 지금 요청의 세션 번호
     * @throws BusinessException 로그인하지 않았으면 401, 비밀번호가 틀리면 400 WRONG_PASSWORD
     */
    @Transactional
    public void withdraw(String password, String currentSessionId) {
        // 지금 로그인한 계정을 얻는다. 로그인하지 않았으면 여기서 401 예외가 난다.
        LoginAccount account = loginAccountResolver.require();
        // 행을 잠그고(FOR UPDATE) 읽는다. 같은 계정의 탈퇴 요청이 동시에 두 번 들어와도 한 번씩 차례로 처리된다.
        UserAccount user = userAccountRepository
                .findByIdForUpdate(account.accountId())
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
        // 처리 순서 1: 비밀번호 확인
        // 입력한 평문과 저장된 해시를 비교한다. 틀리면 탈퇴하지 않는다.
        if (!passwordEncoder.matches(password, user.getPassword())) {
            throw new BusinessException(ErrorCode.WRONG_PASSWORD);
        }
        // 처리 순서 2: status=WITHDRAWN, withdrawn_at=현재 시각
        // 행을 삭제하지 않고 상태만 바꾼다. 시각은 DB 규칙대로 KST다.
        user.withdraw(LocalDateTime.now(KST));
        // 처리 순서 3: 이 계정의 모든 세션 삭제(지금 세션은 컨트롤러가 무효화)
        // 끝낸 세션 수(로그 출력용).
        int ended = 0;
        // 세션 저장소는 로그인 아이디를 키로 이 계정의 모든 세션을 돌려준다(기기 여러 대에서 로그인한 경우 포함).
        for (String sessionId :
                sessionRepository.findByPrincipalName(user.getLoginId()).keySet()) {
            // 지금 세션은 건너뛴다. 여기서 지우면 요청 끝에 Spring Session이 다시 저장해 되살아나므로 컨트롤러가 invalidate한다.
            if (!sessionId.equals(currentSessionId)) {
                sessionRepository.deleteById(sessionId);
                ended++;
            }
        }
        log.info("회원 탈퇴 accountId={} 끊은 다른 세션={}", account.accountId(), ended);
    }
}
