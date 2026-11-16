package com.whiteeyes.admin.account.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.whiteeyes.admin.account.dto.UserListItem;
import com.whiteeyes.admin.account.dto.UserUpdateRequest;
import com.whiteeyes.admin.account.exception.InvalidUserStatusException;
import com.whiteeyes.admin.account.exception.UserNotFoundException;
import com.whiteeyes.admin.account.repository.SessionActivityRepository.SessionTable;
import com.whiteeyes.admin.account.repository.UserRow;
import com.whiteeyes.admin.history.service.AdminWorkRecorder;
import com.whiteeyes.common.account.entity.UserAccount;
import com.whiteeyes.common.account.entity.UserAccountStatus;
import com.whiteeyes.common.account.repository.UserAccountRepository;
import com.whiteeyes.common.history.entity.WorkAction;
import com.whiteeyes.common.history.entity.WorkTargetType;
import com.whiteeyes.common.security.LoginAccount;

/**
 * 관리자가 사용자의 이름·메일·연락처를 고친다(FNC-A-004). 관리자 이상이 부를 수 있다(권한은 AdminSecurityConfig 권한 표).
 * 저장과 작업 기록(ACCOUNT_CHANGE, detail: 바뀐 필드의 이전·이후 값)은 한 트랜잭션이라 기록이 실패하면 수정도 취소된다.
 * 바뀐 값이 없으면 기록하지 않는다(내 정보 수정 FNC-C-006과 같음).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserUpdateService {

    // 409 오류 문구에 들어가는 하려던 일
    private static final String ACTION = "수정";

    // 대상 사용자 조회(행 잠금)
    private final UserAccountRepository userAccountRepository;
    // 응답 항목에 넣을 최근 활동 계산
    private final AccountActivityService accountActivityService;
    // 로그인한 관리자 확인과 작업 기록 저장
    private final AdminWorkRecorder workRecorder;

    /**
     * 보낸 필드만 고친다. 보내지 않은(null) 필드는 저장된 값을 그대로 두고, 메일·연락처를 빈 문자열로 보내면 비운다(NULL 저장).
     * 미승인·정상 계정은 고치고, 탈퇴 계정은 고칠 수 없다(비밀번호 재설정 FNC-A-005와 같음).
     *
     * @return 수정된 사용자(목록 한 줄과 같은 모양)
     * @throws UserNotFoundException 없는 사용자일 때(404)
     * @throws InvalidUserStatusException 탈퇴 계정일 때(409 INVALID_STATUS)
     */
    @Transactional
    public UserListItem update(Long userId, UserUpdateRequest request) {
        // 로그인하지 않은 요청은 값을 바꾸기 전에 거부한다(작업 기록의 행동한 계정을 채울 수 없다)
        LoginAccount actor = workRecorder.requireActor();
        // 처리 순서 1: 대상 조회(없으면 404). 동시에 고치는 관리자끼리 이전 값이 엇갈리지 않게 행을 잠근다
        UserAccount user =
                userAccountRepository.findByIdForUpdate(userId).orElseThrow(() -> new UserNotFoundException(userId));
        // 탈퇴 계정은 수정할 수 없다(409)
        if (user.getStatus() == UserAccountStatus.WITHDRAWN) {
            throw new InvalidUserStatusException(userId, user.getStatus(), ACTION);
        }

        // 처리 순서 2: 검증(요청 검사)한 값을 저장
        // 보낸 필드는 새 값, 안 보낸(null) 필드는 기존 값을 그대로 쓴다. 메일·연락처의 빈 문자열은 NULL로 바꿔 비운다
        String name = request.name() != null ? request.name() : user.getName();
        String email = request.email() != null ? emptyToNull(request.email()) : user.getEmail();
        String phone = request.phone() != null ? emptyToNull(request.phone()) : user.getPhone();
        // 바뀐 필드만 이전 값·이후 값으로 모은다(작업 기록 detail). LinkedHashMap은 기록 순서를 유지한다
        Map<String, Object> before = new LinkedHashMap<>();
        Map<String, Object> after = new LinkedHashMap<>();
        collectChange("name", user.getName(), name, before, after);
        collectChange("email", user.getEmail(), email, before, after);
        collectChange("phone", user.getPhone(), phone, before, after);
        // 엔티티의 값을 바꾼다. 트랜잭션이 끝날 때 JPA가 UPDATE한다
        user.updateProfile(name, email, phone);

        // 처리 순서 3: 작업 기록. 계정의 대상 이름은 아이디다(tables.md work_history.target_name)
        // 바뀐 값이 하나라도 있을 때만 기록한다
        if (!before.isEmpty()) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("before", before);
            detail.put("after", after);
            workRecorder.record(
                    actor, WorkAction.ACCOUNT_CHANGE, WorkTargetType.USER_ACCOUNT, userId, user.getLoginId(), detail);
        }
        // 개인 정보(이름·메일·연락처)는 로그에 남기지 않는다(code-conventions 7-4)
        log.info("사용자 정보 수정 userId={} 바뀐 필드={}", userId, before.keySet());
        // 수정 결과를 목록 한 줄과 같은 모양으로 돌려줘 화면이 바로 갱신할 수 있게 한다
        return toItem(user);
    }

    private UserListItem toItem(UserAccount user) {
        // 엔티티를 목록용 행으로 옮겨 UserListAssembler의 변환을 재사용한다
        UserRow row = new UserRow(
                user.getId(),
                user.getLoginId(),
                user.getName(),
                user.getEmail(),
                user.getPhone(),
                user.getStatus(),
                user.getCreatedAt(),
                user.getLastLoginAt());
        AccountActivities activities = accountActivityService.loadActivities(SessionTable.USER, List.of(row.loginId()));
        return UserListAssembler.assembleOne(row, activities);
    }

    /** 값이 달라졌을 때만 이전 값과 이후 값을 각각 맵에 담는다(null 안전 비교). */
    private static void collectChange(
            String field, String was, String now, Map<String, Object> before, Map<String, Object> after) {
        if (!Objects.equals(was, now)) {
            before.put(field, was);
            after.put(field, now);
        }
    }

    // 빈 문자열이면 NULL로 바꾼다(null은 이 메서드를 부르기 전에 걸러진다)
    private static String emptyToNull(String value) {
        return value.isEmpty() ? null : value;
    }
}
