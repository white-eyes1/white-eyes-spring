/*
 * [해석본] 원본: user/src/main/java/com/whiteeyes/user/account/service/SignupService.java (feature/29-check-login-id 브랜치)
 * 관련 이슈: #29(FNC-U-001)
 *
 * [무엇] 회원가입 쪽 비즈니스 로직. 지금은 '아이디를 쓸 수 있는가' 판단 하나뿐이다.
 * [왜 user 모듈인가] 가입은 사용자 앱에서만 한다(package-structure 3장 user/account: FNC-U-001~004).
 * [이름] 'LoginIdCheckService'가 아니라 SignupService로 지었다. #30(FNC-U-002)의 가입 처리도 이 클래스에 들어가고,
 *   가입할 때 '아이디 중복 재확인'(FNC-U-002 처리 순서 2)에서 이 메서드를 다시 쓸 수 있다.
 * [하는 일] 리포지토리에 존재 여부를 묻고 뒤집어서 돌려준다. 아이디 형식 검사는 컨트롤러에서 이미 끝났다(package-structure 3-1:
 *   입력 형식 검증은 controller, 비즈니스 판단은 service).
 */
package com.whiteeyes.user.account.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.whiteeyes.common.account.repository.UserAccountRepository;

@Service
public class SignupService {

    private final UserAccountRepository userAccountRepository;

    // [해석] 생성자 주입 + private final(code-conventions 3-3). 테스트에서 리포지토리를 바꿔 끼우기도 쉽다.
    public SignupService(UserAccountRepository userAccountRepository) {
        this.userAccountRepository = userAccountRepository;
    }

    // [해석] 메서드 이름: boolean을 돌려주므로 is로 시작(code-conventions 3-2).
    // [해석] readOnly = true: 읽기만 하는 트랜잭션. JPA가 변경 감지를 하지 않아 가볍다.
    // [해석] 탈퇴 계정 포함은 리포지토리 쪽(existsByLoginId에 상태 조건이 없음)에서 이미 보장된다.
    //        여기서 상태를 따로 거르면 안 된다는 뜻으로 아래 주석에 근거를 남겼다.
    // 탈퇴 계정의 아이디도 사용 불가라서 상태를 보지 않는다(FNC-U-001, auth-005)
    @Transactional(readOnly = true)
    public boolean isLoginIdAvailable(String loginId) {
        return !userAccountRepository.existsByLoginId(loginId);
    }
}
