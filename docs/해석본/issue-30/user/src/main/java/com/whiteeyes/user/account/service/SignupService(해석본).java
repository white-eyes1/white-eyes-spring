/*
 * [해석본] 원본: user/src/main/java/com/whiteeyes/user/account/service/SignupService.java (feature/30-signup 브랜치)
 * 관련 이슈: #29(FNC-U-001), #30(FNC-U-002)
 *
 * [무엇] 회원가입 비즈니스 로직. #29의 아이디 확인에 #30이 가입(signup)을 더했다.
 * [동시 가입] 검수 기준 '동시에 같은 아이디로 가입 시 하나만 성공'은 DB 유일 조건이 보장한다. 아래 catch 설명 참고.
 */
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

    // [해석] PasswordEncoder는 common PasswordEncoderConfig(#101)의 빈 하나다(bcrypt 강도 10, FNC-C-005). 여기서 새로 만들지 않는다.
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
    // [해석] 확인과 저장을 한 트랜잭션으로 묶는다. 다만 이것만으로 동시 가입을 막지는 못한다(확인은 잠금 없는 SELECT라서).
    @Transactional
    public SignupResponse signup(SignupRequest request) {
        // [해석] FNC-U-002 처리 순서 2 '아이디 중복 재확인'. 화면에서 확인을 마쳤어도 그 뒤에 누가 가입했을 수 있다.
        //        평소의 중복은 여기서 INSERT 없이 바로 409가 된다.
        if (userAccountRepository.existsByLoginId(request.loginId())) {
            throw new DuplicateLoginIdException();
        }
        UserAccount account = UserAccount.createPending(
                request.loginId(),
                // [해석] bcrypt는 일부러 느리다(수십 ms). INSERT 전에 끝내서, 행을 잡은 채 기다리는 시간을 늘리지 않는다.
                passwordEncoder.encode(request.password()),
                request.name(),
                emptyToNull(request.email()),
                emptyToNull(request.phone()));
        try {
            // [해석] save만 써도 id 방식이 IDENTITY라 바로 INSERT가 나가지만, 그 동작에 기대지 않고 flush로 '여기서 INSERT'를 분명히 했다.
            //        유일 조건 위반이 이 try 안에서 나야 409로 바꿀 수 있다. 커밋 때 나면 메서드 밖이라 잡지 못하고 500이 된다.
            userAccountRepository.saveAndFlush(account);
        // [해석] 두 요청이 거의 동시에 오면 둘 다 위 확인에서 '없음'을 볼 수 있다. 마지막 판단은 DB의 UK(login_id)(tables.md)가 한다:
        //        먼저 INSERT한 쪽이 커밋할 때까지 다른 쪽은 기다렸다가 중복 위반으로 실패한다(MySQL·H2 모두 같은 동작).
        //        그 위반을 미리 확인과 같은 409로 바꿔서 사용자에게는 두 경우가 똑같이 보인다.
        //        동시성 테스트를 20번 돌려 보니 거절된 180건 중 179건이 이 경로였다. 즉 실제 경쟁에서는 미리 확인보다 이쪽이 막는다.
        //        예외가 리포지토리 밖으로 나오면서 트랜잭션은 롤백 전용이 되지만, 어차피 예외를 던지므로 롤백된다.
        } catch (DataIntegrityViolationException ex) {
            // 동시에 가입하면 둘 다 위 확인을 통과할 수 있어 login_id 유일 조건이 막는다. 다른 칼럼의 제약(NOT NULL·길이)은
            // 요청 검증에서 먼저 걸러지므로 여기서 나는 위반은 아이디 중복이다
            throw new DuplicateLoginIdException(ex);
        }
        // [해석] flush 뒤라 id가 채워져 있다. 엔티티 대신 응답 DTO로 바꿔 돌려준다(package-structure 3-3: 변환은 service).
        return new SignupResponse(account.getId(), account.getStatus());
    }

    // 선택 항목을 비워 보내면 빈 문자열 대신 NULL로 저장한다
    // [해석] StringUtils.hasText는 null·''·공백만 있는 값에 false. 공백만 있는 메일·연락처는 검증(@Email·@Pattern)에서 먼저
    //        걸리므로 여기서는 사실상 ''만 NULL이 된다.
    private static String emptyToNull(String value) {
        return StringUtils.hasText(value) ? value : null;
    }
}
