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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 미승인 사용자를 승인한다(FNC-A-006). 상태 변경과 작업 기록은 한 트랜잭션이다. */
@Service
@RequiredArgsConstructor
public class UserApproveService {

    // 명세(FNC-A-006 처리 순서 3)의 detail 표기를 그대로 옮긴다
    private static final Map<String, String> APPROVE_DETAIL = Map.of("status", "PENDING→ACTIVE");

    private final UserAccountRepository userAccountRepository;
    private final AdminWorkRecorder workRecorder;

    @Transactional
    public void approve(Long userId) {
        // 로그인하지 않은 요청은 상태를 바꾸기 전에 거부한다(작업 기록의 행동한 계정을 채울 수 없다)
        LoginAccount actor = workRecorder.requireActor();
        UserAccount user = userAccountRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new UserNotFoundException(userId));

        // 처리 순서 1: 대상 status=PENDING 확인
        if (user.getStatus() != UserAccountStatus.PENDING) {
            throw new InvalidUserStatusException(userId, user.getStatus());
        }
        // 처리 순서 2: status=ACTIVE
        user.approve();
        // 처리 순서 3: 작업 기록. 계정의 대상 이름은 아이디다(tables.md work_history.target_name)
        workRecorder.record(
                actor, WorkAction.ACCOUNT_CHANGE, WorkTargetType.USER_ACCOUNT, userId, user.getLoginId(), APPROVE_DETAIL);
    }
}
