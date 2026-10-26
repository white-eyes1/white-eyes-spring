package com.whiteeyes.admin.account.service;

import com.whiteeyes.admin.account.dto.PasswordResetResponse;
import com.whiteeyes.admin.account.entity.AdminAccount;
import com.whiteeyes.admin.account.entity.AdminRole;
import com.whiteeyes.admin.account.exception.AdminNotFoundException;
import com.whiteeyes.admin.account.exception.SuperAdminTargetException;
import com.whiteeyes.admin.account.repository.AdminAccountRepository;
import com.whiteeyes.admin.history.service.AdminWorkRecorder;
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
 * 관리자(role=ADMIN)에게 임시 비밀번호를 발급한다(FNC-A-010, 처리 순서는 FNC-A-005와 같음).
 * 최고 관리자만 부를 수 있다(권한은 AdminSecurityConfig 권한 표 /api/v1/admins/**).
 * 저장과 작업 기록은 한 트랜잭션이고, 세션 삭제는 그 트랜잭션의 맨 끝에서 한다
 * (관리자 앱 세션 저장소는 삭제를 바로 확정하므로 되돌릴 수 없다. AdminStatusService와 같음).
 *
 * <p>세션 저장소는 웹 앱에만 있으므로 웹 서버 없이 띄우는 1회 작업(--job=…)에서는 등록하지 않는다.
 */
@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequiredArgsConstructor
public class AdminPasswordResetService {

    private static final Logger log = LoggerFactory.getLogger(AdminPasswordResetService.class);

    // 명세(FNC-A-005 처리 순서 4)의 detail 표기를 그대로 옮긴다. 비밀번호 값은 넣지 않는다
    private static final Map<String, String> RESET_DETAIL = Map.of("password", "변경됨");

    private final AdminAccountRepository adminAccountRepository;
    private final PasswordEncoder passwordEncoder;
    private final TemporaryPasswordGenerator temporaryPasswordGenerator;
    private final AccountSessionService accountSessionService;
    private final AdminWorkRecorder workRecorder;

    /**
     * 명세에 상태 제한이 없어 비활성화된 관리자도 재설정한다.
     *
     * @throws AdminNotFoundException 없는 관리자일 때(404)
     * @throws SuperAdminTargetException 최고 관리자일 때(400)
     */
    @Transactional
    public PasswordResetResponse reset(Long adminId) {
        // 로그인하지 않은 요청은 비밀번호를 바꾸기 전에 거부한다(작업 기록의 행동한 계정을 채울 수 없다)
        LoginAccount actor = workRecorder.requireActor();
        AdminAccount admin = adminAccountRepository
                .findByIdForUpdate(adminId)
                .orElseThrow(() -> new AdminNotFoundException(adminId));
        // 업무 규칙: 대상은 role=ADMIN만
        if (admin.getRole() == AdminRole.SUPER_ADMIN) {
            throw new SuperAdminTargetException("비밀번호 재설정");
        }

        // 처리 순서 1·2: 임시 비밀번호는 bcrypt 값만 저장하고 평문은 응답으로 한 번만 돌려준다
        String temporaryPassword = temporaryPasswordGenerator.generate();
        admin.resetPassword(passwordEncoder.encode(temporaryPassword));
        // 처리 순서 4: 작업 기록. 계정의 대상 이름은 아이디다(tables.md work_history.target_name)
        workRecorder.record(
                actor, WorkAction.ACCOUNT_CHANGE, WorkTargetType.ADMIN_ACCOUNT, adminId, admin.getLoginId(), RESET_DETAIL);
        // 처리 순서 3: 대상의 모든 세션 삭제. 되돌릴 수 없어 기록 뒤 맨 끝에 한다
        int deletedSessions = accountSessionService.endAdminSessions(admin.getLoginId());
        log.info("관리자 비밀번호 재설정 adminId={} 삭제한 세션={}", adminId, deletedSessions);
        return new PasswordResetResponse(temporaryPassword);
    }
}
