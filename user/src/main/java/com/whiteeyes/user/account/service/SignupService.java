package com.whiteeyes.user.account.service;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.whiteeyes.common.account.entity.UserAccount;
import com.whiteeyes.common.account.repository.UserAccountRepository;
import com.whiteeyes.user.account.dto.SignupRequest;
import com.whiteeyes.user.account.dto.SignupResponse;
import com.whiteeyes.user.account.exception.DuplicateLoginIdException;

@Service
public class SignupService {

    private final UserAccountRepository userAccountRepository;
    private final PasswordEncoder passwordEncoder;

    public SignupService(UserAccountRepository userAccountRepository, PasswordEncoder passwordEncoder) {
        this.userAccountRepository = userAccountRepository;
        this.passwordEncoder = passwordEncoder;
    }

    // 탈퇴 계정의 아이디도 사용 불가라서 상태를 보지 않는다(FNC-U-001, auth-005)
    @Transactional(readOnly = true)
    public boolean isLoginIdAvailable(String loginId) {
        return !userAccountRepository.existsByLoginId(loginId);
    }

    /**
     * 사용자 계정을 PENDING으로 만든다(FNC-U-002). 관리자가 승인해야 서비스를 쓸 수 있다.
     *
     * @throws DuplicateLoginIdException 같은 아이디의 계정이 있을 때(탈퇴 계정 포함)
     */
    @Transactional
    public SignupResponse signup(SignupRequest request) {
        if (userAccountRepository.existsByLoginId(request.loginId())) {
            throw new DuplicateLoginIdException();
        }
        UserAccount account = UserAccount.createPending(
                request.loginId(),
                passwordEncoder.encode(request.password()),
                request.name(),
                emptyToNull(request.email()),
                emptyToNull(request.phone()));
        try {
            userAccountRepository.saveAndFlush(account);
        } catch (DataIntegrityViolationException ex) {
            // 동시에 가입하면 둘 다 위 확인을 통과할 수 있어 login_id 유일 조건이 막는다. 다른 칼럼의 제약(NOT NULL·길이)은
            // 요청 검증에서 먼저 걸러지므로 여기서 나는 위반은 아이디 중복이다
            throw new DuplicateLoginIdException(ex);
        }
        return new SignupResponse(account.getId(), account.getStatus());
    }

    // 선택 항목을 비워 보내면 빈 문자열 대신 NULL로 저장한다
    private static String emptyToNull(String value) {
        return StringUtils.hasText(value) ? value : null;
    }
}
