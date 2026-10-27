package com.whiteeyes.admin.account.service;

import com.whiteeyes.admin.account.dto.PasswordResetResponse;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 사용자에게 임시 비밀번호를 발급한다(FNC-A-005). 관리자 이상이 부를 수 있다(권한은 AdminSecurityConfig 권한 표).
 * 저장·세션 삭제·작업 기록은 한 트랜잭션이라 하나라도 실패하면 모두 취소된다.
 *
 * <p>세션 저장소는 웹 앱에만 있으므로 웹 서버 없이 띄우는 1회 작업(--job=…)에서는 등록하지 않는다.
 */
@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequiredArgsConstructor
public class UserPasswordResetService {

    private static final Logger log = LoggerFactory.getLogger(UserPasswordResetService.class);

    // 명세(FNC-A-005 처리 순서 4)의 detail 표기를 그대로 옮긴다. 비밀번호 값은 넣지 않는다
    private static final Map<String, String> RESET_DETAIL = Map.of("password", "변경됨");

    // 409 오류 문구에 들어가는 하려던 일
    private static final String ACTION = "비밀번호를 재설정";

    private final UserAccountRepository userAccountRepository;
    private final PasswordEncoder passwordEncoder;
    private final TemporaryPasswordGenerator temporaryPasswordGenerator;
    private final AccountSessionService accountSessionService;
    private final AdminWorkRecorder workRecorder;

    /**
     * 미승인·정상 계정만 재설정한다. 탈퇴 계정은 로그인할 수 없어 임시 비밀번호를 쓸 곳이 없다.
     *
     * @throws UserNotFoundException 없는 사용자일 때(404)
     * @throws InvalidUserStatusException 탈퇴 계정일 때(409 INVALID_STATUS)
     */
    @Transactional
    public PasswordResetResponse reset(Long userId) {
        // 로그인하지 않은 요청은 비밀번호를 바꾸기 전에 거부한다(작업 기록의 행동한 계정을 채울 수 없다)
        LoginAccount actor = workRecorder.requireActor();
        UserAccount user = userAccountRepository
                .findByIdForUpdate(userId)
                .orElseThrow(() -> new UserNotFoundException(userId));
        if (user.getStatus() == UserAccountStatus.WITHDRAWN) {
            throw new InvalidUserStatusException(userId, user.getStatus(), ACTION);
        }

        // 처리 순서 2·3: 임시 비밀번호는 bcrypt 값만 저장하고 평문은 응답으로 한 번만 돌려준다
        String temporaryPassword = temporaryPasswordGenerator.generate();
        user.resetPassword(passwordEncoder.encode(temporaryPassword));
        // 처리 순서 4: 대상의 모든 세션 삭제(사용자 앱 세션 표, 호출한 쪽 트랜잭션에 합쳐짐)
        int deletedSessions = accountSessionService.endUserSessions(user.getLoginId());
        // 처리 순서 5: 작업 기록. 계정의 대상 이름은 아이디다(tables.md work_history.target_name)
        workRecorder.record(
                actor, WorkAction.ACCOUNT_CHANGE, WorkTargetType.USER_ACCOUNT, userId, user.getLoginId(), RESET_DETAIL);
        log.info("사용자 비밀번호 재설정 userId={} 삭제한 세션={}", userId, deletedSessions);
        return new PasswordResetResponse(temporaryPassword);
    }
}
