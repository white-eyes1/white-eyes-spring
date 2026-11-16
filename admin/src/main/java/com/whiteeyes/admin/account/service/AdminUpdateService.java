package com.whiteeyes.admin.account.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.whiteeyes.admin.account.dto.AdminListItem;
import com.whiteeyes.admin.account.dto.AdminUpdateRequest;
import com.whiteeyes.admin.account.entity.AdminAccount;
import com.whiteeyes.admin.account.entity.AdminRole;
import com.whiteeyes.admin.account.exception.AdminNotFoundException;
import com.whiteeyes.admin.account.exception.SuperAdminTargetException;
import com.whiteeyes.admin.account.repository.AdminAccountRepository;
import com.whiteeyes.admin.account.repository.AdminRow;
import com.whiteeyes.admin.account.repository.SessionActivityRepository.SessionTable;
import com.whiteeyes.admin.history.service.AdminWorkRecorder;
import com.whiteeyes.common.history.entity.WorkAction;
import com.whiteeyes.common.history.entity.WorkTargetType;
import com.whiteeyes.common.security.LoginAccount;

/**
 * 최고 관리자가 관리자(role=ADMIN)의 이름·메일·연락처를 고친다(FNC-A-010, 처리 순서는 FNC-A-004와 같음).
 * 최고 관리자만 부를 수 있다(권한은 AdminSecurityConfig 권한 표 /api/v1/admins/**).
 * 저장과 작업 기록(ACCOUNT_CHANGE, detail: 바뀐 필드의 이전·이후 값)은 한 트랜잭션이라 기록이 실패하면 수정도 취소된다.
 * 바뀐 값이 없으면 기록하지 않는다(사용자 정보 수정 FNC-A-004와 같음).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminUpdateService {

    // 대상 관리자 조회(행 잠금)
    private final AdminAccountRepository adminAccountRepository;
    // 응답 항목에 넣을 최근 활동 계산
    private final AccountActivityService accountActivityService;
    // 로그인한 관리자 확인과 작업 기록 저장
    private final AdminWorkRecorder workRecorder;

    /**
     * 보낸 필드만 고친다. 보내지 않은(null) 필드는 저장된 값을 그대로 두고, 메일·연락처를 빈 문자열로 보내면 비운다(NULL 저장).
     * 명세에 상태 제한이 없어 비활성화된 관리자도 고친다(비밀번호 재설정과 같음).
     *
     * @return 수정된 관리자(목록 한 줄과 같은 모양)
     * @throws AdminNotFoundException 없는 관리자일 때(404)
     * @throws SuperAdminTargetException 최고 관리자일 때(400). 최고 관리자 자신의 정보는 내 정보 화면에서 고친다
     */
    @Transactional
    public AdminListItem update(Long adminId, AdminUpdateRequest request) {
        // 로그인하지 않은 요청은 값을 바꾸기 전에 거부한다(작업 기록의 행동한 계정을 채울 수 없다)
        LoginAccount actor = workRecorder.requireActor();
        // 처리 순서 1: 대상 조회(없으면 404). 동시에 고치는 최고 관리자끼리 이전 값이 엇갈리지 않게 행을 잠근다
        AdminAccount admin = adminAccountRepository
                .findByIdForUpdate(adminId)
                .orElseThrow(() -> new AdminNotFoundException(adminId));
        // 업무 규칙: 대상은 role=ADMIN만
        if (admin.getRole() == AdminRole.SUPER_ADMIN) {
            throw new SuperAdminTargetException("정보 수정");
        }

        // 처리 순서 2: 검증(요청 검사)한 값을 저장
        // 보낸 필드는 새 값, 안 보낸(null) 필드는 기존 값을 그대로 쓴다. 메일·연락처의 빈 문자열은 NULL로 바꿔 비운다
        String name = request.name() != null ? request.name() : admin.getName();
        String email = request.email() != null ? emptyToNull(request.email()) : admin.getEmail();
        String phone = request.phone() != null ? emptyToNull(request.phone()) : admin.getPhone();
        // 바뀐 필드만 이전 값·이후 값으로 모은다(작업 기록 detail). LinkedHashMap은 기록 순서를 유지한다
        Map<String, Object> before = new LinkedHashMap<>();
        Map<String, Object> after = new LinkedHashMap<>();
        collectChange("name", admin.getName(), name, before, after);
        collectChange("email", admin.getEmail(), email, before, after);
        collectChange("phone", admin.getPhone(), phone, before, after);
        // 엔티티의 값을 바꾼다. 트랜잭션이 끝날 때 JPA가 UPDATE한다
        admin.updateProfile(name, email, phone);

        // 처리 순서 3: 작업 기록. 계정의 대상 이름은 아이디다(tables.md work_history.target_name)
        // 바뀐 값이 하나라도 있을 때만 기록한다
        if (!before.isEmpty()) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("before", before);
            detail.put("after", after);
            workRecorder.record(
                    actor,
                    WorkAction.ACCOUNT_CHANGE,
                    WorkTargetType.ADMIN_ACCOUNT,
                    adminId,
                    admin.getLoginId(),
                    detail);
        }
        // 개인 정보(이름·메일·연락처)는 로그에 남기지 않는다(code-conventions 7-4)
        log.info("관리자 정보 수정 adminId={} 바뀐 필드={}", adminId, before.keySet());
        // 수정 결과를 목록 한 줄과 같은 모양으로 돌려줘 화면이 바로 갱신할 수 있게 한다
        return toItem(admin);
    }

    private AdminListItem toItem(AdminAccount admin) {
        // 엔티티를 목록용 행으로 옮겨 AdminListService와 같은 변환(toItem)을 재사용한다
        AdminRow row = new AdminRow(
                admin.getId(),
                admin.getLoginId(),
                admin.getName(),
                admin.getEmail(),
                admin.getPhone(),
                admin.getRole(),
                admin.getStatus(),
                admin.getCreatedAt(),
                admin.getLastLoginAt());
        // 이 관리자 한 명의 세션만 조회한다
        AccountActivities activities =
                accountActivityService.loadActivities(SessionTable.ADMIN, List.of(row.loginId()));
        return AdminListService.toItem(row, activities);
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
