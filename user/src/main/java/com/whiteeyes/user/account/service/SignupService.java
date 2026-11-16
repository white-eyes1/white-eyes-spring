package com.whiteeyes.user.account.service;

import lombok.RequiredArgsConstructor;
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

/**
 * 회원가입(FNC-U-001·002) 처리. 아이디 사용 가능 여부 확인과 PENDING 계정 생성을 맡는다.
 */
@Service
@RequiredArgsConstructor
public class SignupService {

    private final UserAccountRepository userAccountRepository;
    private final PasswordEncoder passwordEncoder;

    /**
     * 아이디를 쓸 수 있는지 확인한다(FNC-U-001).
     *
     * @return 같은 아이디가 없으면 true
     */
    // 탈퇴 계정의 아이디도 사용 불가라서 상태를 보지 않는다(FNC-U-001, auth-005)
    @Transactional(readOnly = true)
    public boolean isLoginIdAvailable(String loginId) {
        // 존재하지 않을 때가 "사용 가능"이라 결과를 뒤집는다.
        return !userAccountRepository.existsByLoginId(loginId);
    }

    /**
     * 사용자 계정을 PENDING으로 만든다(FNC-U-002). 관리자가 승인해야 서비스를 쓸 수 있다.
     *
     * @throws DuplicateLoginIdException 같은 아이디의 계정이 있을 때(탈퇴 계정 포함)
     */
    @Transactional
    public SignupResponse signup(SignupRequest request) {
        // 1차 중복 확인. 대부분의 중복은 여기서 걸러 의미 있는 오류(409 계열)로 알린다.
        if (userAccountRepository.existsByLoginId(request.loginId())) {
            throw new DuplicateLoginIdException();
        }
        // 비밀번호는 평문이 아니라 암호화한 값만 엔티티에 담는다. 새 계정은 PENDING(승인 대기) 상태로 만든다.
        UserAccount account = UserAccount.createPending(
                request.loginId(),
                passwordEncoder.encode(request.password()),
                request.name(),
                emptyToNull(request.email()),
                emptyToNull(request.phone()));
        // saveAndFlush로 바로 INSERT를 실행해, 유일 조건 위반이 트랜잭션 끝이 아니라 이 try 안에서 드러나게 한다.
        try {
            userAccountRepository.saveAndFlush(account);
        } catch (DataIntegrityViolationException ex) {
            // 동시에 가입하면 둘 다 위 확인을 통과할 수 있어 login_id 유일 조건이 막는다. 다른 칼럼의 제약(NOT NULL·길이)은
            // 요청 검증에서 먼저 걸러지므로 여기서 나는 위반은 아이디 중복이다
            throw new DuplicateLoginIdException(ex);
        }
        // 저장된 뒤라 계정 번호(id)가 채워져 있다. 번호와 상태만 돌려준다.
        return new SignupResponse(account.getId(), account.getStatus());
    }

    // 선택 항목을 비워 보내면 빈 문자열 대신 NULL로 저장한다
    private static String emptyToNull(String value) {
        return StringUtils.hasText(value) ? value : null;
    }
}
