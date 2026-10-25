package com.whiteeyes.admin.account.service;

import com.whiteeyes.admin.account.exception.InvalidUserStatusException;
import com.whiteeyes.admin.account.exception.UserNotFoundException;
import com.whiteeyes.admin.history.service.AdminWorkRecorder;
import com.whiteeyes.common.account.entity.UserAccount;
import com.whiteeyes.common.account.entity.UserAccountStatus;
import com.whiteeyes.common.account.repository.UserAccountRepository;
import com.whiteeyes.common.history.entity.WorkAction;
import com.whiteeyes.common.history.entity.WorkTargetType;
import com.whiteeyes.common.security.LoginAccount;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 사용자 승인을 취소해 미승인으로 되돌리고 바로 차단한다(FNC-A-007). 관리자 이상이 부를 수 있다(권한은 AdminSecurityConfig 권한 표).
 * 상태 변경·세션 삭제·작업 기록은 한 트랜잭션이라 하나라도 실패하면 모두 취소된다.
 * 세션을 지운 브라우저는 다음 요청에서 로그인 화면으로 가고, 다시 로그인하면 승인 대기 화면으로 간다(FNC-U-003).
 *
 * <p>세션 저장소는 웹 앱에만 있으므로 웹 서버 없이 띄우는 1회 작업(--job=…)에서는 등록하지 않는다.
 */
@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequiredArgsConstructor
public class UserUnapproveService {

    private static final Logger log = LoggerFactory.getLogger(UserUnapproveService.class);

    // 승인(FNC-A-006)의 detail과 같은 표기로 반대 방향을 남긴다
    private static final Map<String, String> UNAPPROVE_DETAIL = Map.of("status", "ACTIVE→PENDING");
    private static final String ACTION = "승인 취소";

    private final UserAccountRepository userAccountRepository;
    private final AccountSessionService accountSessionService;
    private final AdminWorkRecorder workRecorder;

    /**
     * @throws UserNotFoundException 없는 사용자일 때(404)
     * @throws InvalidUserStatusException ACTIVE가 아닐 때(409 INVALID_STATUS)
     */
    @Transactional
    public void unapprove(Long userId) {
        // 로그인하지 않은 요청은 상태를 바꾸기 전에 거부한다(작업 기록의 행동한 계정을 채울 수 없다)
        LoginAccount actor = workRecorder.requireActor();
        UserAccount user = userAccountRepository
                .findByIdForUpdate(userId)
                .orElseThrow(() -> new UserNotFoundException(userId));

        // 처리 순서 1: 대상 status=ACTIVE 확인
        if (user.getStatus() != UserAccountStatus.ACTIVE) {
            throw new InvalidUserStatusException(userId, user.getStatus(), ACTION);
        }
        // 처리 순서 2: status=PENDING
        user.unapprove();
        // 처리 순서 3: 대상의 모든 세션 삭제(사용자 앱 세션 표, 호출한 쪽 트랜잭션에 합쳐짐)
        int deletedSessions = accountSessionService.endUserSessions(user.getLoginId());
        // 처리 순서 4: 작업 기록. 계정의 대상 이름은 아이디다(tables.md work_history.target_name)
        workRecorder.record(
                actor, WorkAction.ACCOUNT_CHANGE, WorkTargetType.USER_ACCOUNT, userId, user.getLoginId(), UNAPPROVE_DETAIL);
        log.info("사용자 승인 취소 userId={} 삭제한 세션={}", userId, deletedSessions);
    }
}
