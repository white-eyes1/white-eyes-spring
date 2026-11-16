package com.whiteeyes.common.account.service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.whiteeyes.common.account.dto.MyInfoResponse;
import com.whiteeyes.common.account.dto.MyInfoUpdateRequest;
import com.whiteeyes.common.exception.BusinessException;
import com.whiteeyes.common.exception.ErrorCode;
import com.whiteeyes.common.history.entity.ActorType;
import com.whiteeyes.common.history.entity.WorkAction;
import com.whiteeyes.common.history.entity.WorkTargetType;
import com.whiteeyes.common.history.service.WorkActorTypeProvider;
import com.whiteeyes.common.history.service.WorkRecorder;
import com.whiteeyes.common.security.LoginAccount;
import com.whiteeyes.common.security.LoginAccountResolver;

/**
 * 내 정보 조회·수정(FNC-C-006). 로그인한 본인의 행만 읽고 쓴다. 아이디·역할·상태는 바꾸지 않는다.
 *
 * 수정하면 작업 기록(FNC-C-026, ACCOUNT_CHANGE)을 같은 트랜잭션으로 남긴다. 행동한 계정과 대상은 모두 본인이고,
 * detail은 바뀐 필드의 이전·이후 값이다. 바뀐 값이 없으면 기록하지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MyInfoService {

    // 현재 로그인한 계정(번호·아이디)을 세션에서 꺼내는 도구
    private final LoginAccountResolver loginAccountResolver;
    // 사용자 앱·관리자 앱이 각자 구현한 계정 읽기·쓰기 입구
    private final AccountProfileStore accountProfileStore;
    // 작업 기록(FNC-C-026)을 남기는 도구
    private final WorkRecorder workRecorder;
    // 이 앱이 사용자 앱인지 관리자 앱인지 알려 주는 도구. 작업 기록의 대상 종류를 고르는 데 쓴다
    private final WorkActorTypeProvider actorTypeProvider;

    // 조회만 하므로 읽기 전용 트랜잭션으로 연다
    /** @throws BusinessException 로그인하지 않았으면 401 */
    @Transactional(readOnly = true)
    public MyInfoResponse get() {
        return toResponse(find(loginAccountResolver.require()));
    }

    /**
     * 보낸 필드만 고친다. 보내지 않은(null) 필드는 저장된 값을 그대로 두고,
     * 메일·연락처를 빈 문자열로 보내면 비운다(NULL 저장). 이름의 빈 문자열은 요청 검사에서 먼저 걸러진다.
     *
     * 저장한 뒤 바뀐 필드가 있으면 작업 기록을 남긴다. 기록이 실패하면 예외가 그대로 올라가 수정도 취소된다.
     *
     * @throws BusinessException 로그인하지 않았거나 계정 행이 없으면 401
     */
    @Transactional
    public MyInfoResponse update(MyInfoUpdateRequest request) {
        // 로그인한 본인 정보. 로그인하지 않았으면 여기서 401 예외가 난다
        LoginAccount account = loginAccountResolver.require();
        // 수정 전 값. 보내지 않은 칸을 유지하고, 바뀐 칸만 작업 기록에 남길 때 비교하는 기준이다
        AccountProfile current = find(account);

        // 이름: 보냈으면 새 값, 안 보냈으면 기존 값
        String name = request.name() != null ? request.name() : current.name();
        // 메일: 보냈으면 새 값(빈 문자열은 null로 바꿔 비움), 안 보냈으면 기존 값
        String email = request.email() != null ? emptyToNull(request.email()) : current.email();
        // 연락처도 메일과 같은 규칙
        String phone = request.phone() != null ? emptyToNull(request.phone()) : current.phone();
        // 계정 테이블에 저장한다. 어느 테이블인지는 앱이 구현한 저장소가 정한다
        accountProfileStore.updateProfile(account.accountId(), name, email, phone);
        // 처리 순서 4: 작업 기록(바뀐 필드의 이전·이후 값). 바뀐 값이 없으면 기록하지 않는다
        recordChange(account, current, name, email, phone);
        // 개인 정보(이름·메일·연락처)는 로그에 남기지 않는다(code-conventions 7-4)
        log.info("내 정보 수정 accountId={}", account.accountId());

        return toResponse(find(account));
    }

    /** 바뀐 필드의 이전·이후 값을 모아 작업 기록 한 건으로 남긴다. 바뀐 것이 없으면 아무것도 남기지 않는다. */
    private void recordChange(LoginAccount account, AccountProfile current, String name, String email, String phone) {
        // 필드 순서가 기록에 그대로 남도록 순서를 유지하는 맵을 쓴다
        Map<String, Object> before = new LinkedHashMap<>();
        Map<String, Object> after = new LinkedHashMap<>();
        // 세 필드를 같은 방식으로 비교해 달라진 것만 before·after에 담는다
        collectChange("name", current.name(), name, before, after);
        collectChange("email", current.email(), email, before, after);
        collectChange("phone", current.phone(), phone, before, after);
        // 달라진 값이 없으면 기록할 일이 없다
        if (before.isEmpty()) {
            return;
        }
        // 대상은 본인 계정이다. 계정의 대상 이름은 아이디다(tables.md work_history.target_name)
        // 관리자 앱이면 관리자 계정, 아니면 사용자 계정이 대상이다
        WorkTargetType targetType = actorTypeProvider.actorType() == ActorType.ADMIN
                ? WorkTargetType.ADMIN_ACCOUNT
                : WorkTargetType.USER_ACCOUNT;
        // detail 칸에 JSON으로 저장될 내용: {"before": {...}, "after": {...}}
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("before", before);
        detail.put("after", after);
        // 행동한 사람과 대상이 모두 본인이다
        workRecorder.record(
                account, WorkAction.ACCOUNT_CHANGE, targetType, account.accountId(), account.loginId(), detail);
    }

    /** 이전 값과 새 값이 다르면 before·after 맵에 같은 칸 이름으로 담는다. null끼리는 같다고 본다(Objects.equals). */
    private void collectChange(
            String field, String was, String now, Map<String, Object> before, Map<String, Object> after) {
        if (!Objects.equals(was, now)) {
            before.put(field, was);
            after.put(field, now);
        }
    }

    /** 계정 행을 읽는다. 세션은 있는데 행이 없으면(삭제 등) 인증이 무효이므로 401로 처리한다. */
    private AccountProfile find(LoginAccount account) {
        return accountProfileStore
                .findProfile(account.accountId())
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
    }

    /** 빈 문자열을 null로 바꾼다. 화면에서 칸을 비우면 DB에는 NULL로 저장하기 위함이다. */
    private String emptyToNull(String value) {
        return value.isEmpty() ? null : value;
    }

    /** 서비스 내부 값(AccountProfile)을 응답 형태로 옮긴다. */
    private MyInfoResponse toResponse(AccountProfile profile) {
        return new MyInfoResponse(
                profile.id(),
                profile.loginId(),
                profile.name(),
                profile.email(),
                profile.phone(),
                profile.status(),
                profile.mustChangePassword(),
                profile.role());
    }
}
