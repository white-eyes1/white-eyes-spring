package com.whiteeyes.admin.account.service;

import java.util.Map;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.whiteeyes.admin.account.entity.AdminAccount;
import com.whiteeyes.admin.account.entity.AdminRole;
import com.whiteeyes.admin.account.entity.AdminStatus;
import com.whiteeyes.admin.account.exception.AdminNotFoundException;
import com.whiteeyes.admin.account.exception.InvalidAdminStatusException;
import com.whiteeyes.admin.account.exception.SuperAdminTargetException;
import com.whiteeyes.admin.account.repository.AdminAccountRepository;
import com.whiteeyes.admin.history.service.AdminWorkRecorder;
import com.whiteeyes.common.history.entity.WorkAction;
import com.whiteeyes.common.history.entity.WorkTargetType;
import com.whiteeyes.common.security.LoginAccount;

/**
 * 관리자 계정을 비활성화하거나 다시 활성화한다(FNC-A-011). 최고 관리자만 부를 수 있다
 * (권한은 AdminSecurityConfig 권한 표 /api/v1/admins/**가 막는다). 상태 변경과 작업 기록은 한 트랜잭션이고,
 * 세션 삭제는 그 트랜잭션의 맨 끝에서 한다(관리자 앱 세션 저장소는 삭제를 바로 확정한다).
 * 비활성화된 관리자가 남긴 기록은 그대로 둔다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminStatusService {

    // 명세(FNC-A-011 처리 순서 3)의 detail 표기를 따른다(FNC-A-006의 'PENDING→ACTIVE'와 같은 형식)
    private static final Map<String, String> DISABLE_DETAIL = Map.of("status", "ACTIVE→DISABLED");
    private static final Map<String, String> ENABLE_DETAIL = Map.of("status", "DISABLED→ACTIVE");

    private final AdminAccountRepository adminAccountRepository;
    private final AccountSessionService accountSessionService;
    private final AdminWorkRecorder workRecorder;

    /** ACTIVE → DISABLED로 바꾸고 그 관리자의 세션을 모두 지운다. 최고 관리자는 대상이 될 수 없다. */
    @Transactional
    public void disable(Long adminId) {
        // 로그인하지 않은 요청은 상태를 바꾸기 전에 거부한다(작업 기록의 행동한 계정을 채울 수 없다)
        LoginAccount actor = workRecorder.requireActor();
        AdminAccount admin = adminAccountRepository
                .findByIdForUpdate(adminId)
                .orElseThrow(() -> new AdminNotFoundException(adminId));

        // 업무 규칙: 최고 관리자 계정은 대상 불가
        if (admin.getRole() == AdminRole.SUPER_ADMIN) {
            throw new SuperAdminTargetException("비활성화");
        }
        if (admin.getStatus() != AdminStatus.ACTIVE) {
            throw new InvalidAdminStatusException(adminId, admin.getStatus(), "비활성화");
        }
        // 처리 순서 1: ACTIVE→DISABLED, 모든 세션 삭제
        admin.disable();
        // 처리 순서 3: 작업 기록. 계정의 대상 이름은 아이디다(tables.md work_history.target_name)
        workRecorder.record(
                actor,
                WorkAction.ACCOUNT_CHANGE,
                WorkTargetType.ADMIN_ACCOUNT,
                adminId,
                admin.getLoginId(),
                DISABLE_DETAIL);
        // 관리자 앱의 세션 저장소는 자기 트랜잭션으로 바로 확정하므로(되돌릴 수 없음) 세션 삭제는 맨 뒤에 한다.
        // 기록이 실패하면 세션이 그대로 남고, 삭제가 실패하면 상태 변경과 기록이 취소된다
        int deleted = accountSessionService.endAdminSessions(admin.getLoginId());
        log.info("관리자 비활성화 adminId={} 삭제한 세션={}", adminId, deleted);
    }

    /** DISABLED → ACTIVE로 바꾼다. */
    @Transactional
    public void enable(Long adminId) {
        LoginAccount actor = workRecorder.requireActor();
        AdminAccount admin = adminAccountRepository
                .findByIdForUpdate(adminId)
                .orElseThrow(() -> new AdminNotFoundException(adminId));

        if (admin.getStatus() != AdminStatus.DISABLED) {
            throw new InvalidAdminStatusException(adminId, admin.getStatus(), "활성화");
        }
        // 처리 순서 2: DISABLED→ACTIVE
        admin.enable();
        workRecorder.record(
                actor,
                WorkAction.ACCOUNT_CHANGE,
                WorkTargetType.ADMIN_ACCOUNT,
                adminId,
                admin.getLoginId(),
                ENABLE_DETAIL);
        log.info("관리자 활성화 adminId={}", adminId);
    }
}
