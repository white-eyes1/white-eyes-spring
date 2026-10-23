package com.whiteeyes.admin.account.service;

import com.whiteeyes.admin.account.dto.AdminCreateRequest;
import com.whiteeyes.admin.account.dto.AdminCreateResponse;
import com.whiteeyes.admin.account.entity.AdminAccount;
import com.whiteeyes.admin.account.exception.DuplicateAdminLoginIdException;
import com.whiteeyes.admin.account.repository.AdminAccountRepository;
import com.whiteeyes.admin.history.service.AdminWorkRecorder;
import com.whiteeyes.common.history.entity.WorkAction;
import com.whiteeyes.common.history.entity.WorkTargetType;
import com.whiteeyes.common.security.LoginAccount;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 최고 관리자가 관리자 계정을 만든다(FNC-A-009). 저장과 작업 기록은 한 트랜잭션이다.
 * 최고 관리자만 부를 수 있다(권한 표 /api/v1/admins/**).
 */
@Service
@RequiredArgsConstructor
public class AdminCreateService {

    private final AdminAccountRepository adminAccountRepository;
    private final PasswordEncoder passwordEncoder;
    private final TemporaryPasswordGenerator temporaryPasswordGenerator;
    private final AdminWorkRecorder workRecorder;

    /**
     * @throws DuplicateAdminLoginIdException 같은 아이디의 관리자 계정이 있을 때(비활성화 계정 포함)
     */
    @Transactional
    public AdminCreateResponse create(AdminCreateRequest request) {
        // 로그인하지 않은 요청은 계정을 만들기 전에 거부한다(만든 사람·작업 기록을 채울 수 없다)
        LoginAccount actor = workRecorder.requireActor();

        // 처리 순서 1: 아이디 규칙은 요청 검증이, 중복은 여기서 본다. 사용자 계정 아이디와는 별개다(tables.md)
        if (adminAccountRepository.existsByLoginId(request.loginId())) {
            throw new DuplicateAdminLoginIdException();
        }
        // 처리 순서 2·3: 임시 비밀번호는 bcrypt 값만 저장하고 평문은 응답으로 한 번만 돌려준다
        String temporaryPassword = temporaryPasswordGenerator.generate();
        AdminAccount account = AdminAccount.createAdmin(
                request.loginId(),
                passwordEncoder.encode(temporaryPassword),
                request.name().strip(),
                emptyToNull(request.email()),
                emptyToNull(request.phone()),
                actor.accountId());
        try {
            adminAccountRepository.saveAndFlush(account);
        } catch (DataIntegrityViolationException ex) {
            // 동시에 만들면 둘 다 위 확인을 통과할 수 있어 login_id 유일 조건이 막는다(회원가입과 같은 처리)
            throw new DuplicateAdminLoginIdException(ex);
        }

        // 처리 순서 4: 작업 기록. 대상 이름은 아이디, 비밀번호 값은 넣지 않는다(#34에서 정함)
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("op", "CREATE");
        detail.put("role", account.getRole());
        detail.put("password", "발급됨");
        workRecorder.record(
                actor, WorkAction.ACCOUNT_CHANGE, WorkTargetType.ADMIN_ACCOUNT, account.getId(), account.getLoginId(), detail);
        return new AdminCreateResponse(account.getId(), temporaryPassword);
    }

    // 선택 항목을 비워 보내면 빈 문자열 대신 NULL로 저장한다
    private static String emptyToNull(String value) {
        return StringUtils.hasText(value) ? value : null;
    }
}
