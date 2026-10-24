package com.whiteeyes.admin.account.service;

import com.whiteeyes.admin.account.dto.ForceLogoutResponse;
import com.whiteeyes.admin.account.entity.AdminAccount;
import com.whiteeyes.admin.account.exception.AdminNotFoundException;
import com.whiteeyes.admin.account.exception.ForceLogoutSelfException;
import com.whiteeyes.admin.account.exception.UserNotFoundException;
import com.whiteeyes.admin.account.repository.AdminAccountRepository;
import com.whiteeyes.admin.history.service.AdminWorkRecorder;
import com.whiteeyes.common.account.entity.UserAccount;
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
 * 접속 중인 사용자 또는 관리자를 강제로 로그아웃시킨다(FNC-A-008). 사용자는 관리자 이상, 관리자는 최고 관리자만 부를 수 있다
 * (권한은 AdminSecurityConfig 권한 표가 막는다). 세션 삭제와 작업 기록은 한 트랜잭션이다.
 *
 * <p>세션 저장소는 웹 앱에만 있으므로 웹 서버 없이 띄우는 1회 작업(--job=…)에서는 등록하지 않는다.
 */
@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequiredArgsConstructor
public class ForceLogoutService {

    private static final Logger log = LoggerFactory.getLogger(ForceLogoutService.class);

    private final UserAccountRepository userAccountRepository;
    private final AdminAccountRepository adminAccountRepository;
    private final AccountSessionService accountSessionService;
    private final AdminWorkRecorder workRecorder;

    /** 사용자의 세션을 모두 지운다. 세션이 없어도 200(0건)이고 작업 기록은 남긴다. */
    @Transactional
    public ForceLogoutResponse logoutUser(Long userId) {
        // 로그인하지 않은 요청은 세션을 지우기 전에 거부한다(작업 기록의 행동한 계정을 채울 수 없다)
        LoginAccount actor = workRecorder.requireActor();
        UserAccount user = userAccountRepository.findById(userId).orElseThrow(() -> new UserNotFoundException(userId));

        // 처리 순서 2: 사용자 앱 세션 표에서 login_id로 찾아 모두 삭제
        int deleted = accountSessionService.endUserSessions(user.getLoginId());
        // 처리 순서 3: 작업 기록. 계정의 대상 이름은 아이디다(tables.md work_history.target_name)
        workRecorder.record(
                actor,
                WorkAction.ACCOUNT_CHANGE,
                WorkTargetType.USER_ACCOUNT,
                userId,
                user.getLoginId(),
                Map.of("deletedSessions", deleted));
        log.info("사용자 강제 로그아웃 userId={} 삭제한 세션={}", userId, deleted);
        return new ForceLogoutResponse(deleted);
    }

    /** 관리자의 세션을 모두 지운다. 자기 자신은 대상이 될 수 없다. */
    @Transactional
    public ForceLogoutResponse logoutAdmin(Long adminId) {
        LoginAccount actor = workRecorder.requireActor();
        // 업무 규칙: 자기 자신은 대상 불가
        if (actor.accountId().equals(adminId)) {
            throw new ForceLogoutSelfException();
        }
        AdminAccount admin =
                adminAccountRepository.findById(adminId).orElseThrow(() -> new AdminNotFoundException(adminId));

        // 처리 순서 2: 관리자 앱 세션 표에서 login_id로 찾아 모두 삭제
        int deleted = accountSessionService.endAdminSessions(admin.getLoginId());
        workRecorder.record(
                actor,
                WorkAction.ACCOUNT_CHANGE,
                WorkTargetType.ADMIN_ACCOUNT,
                adminId,
                admin.getLoginId(),
                Map.of("deletedSessions", deleted));
        log.info("관리자 강제 로그아웃 adminId={} 삭제한 세션={}", adminId, deleted);
        return new ForceLogoutResponse(deleted);
    }
}
