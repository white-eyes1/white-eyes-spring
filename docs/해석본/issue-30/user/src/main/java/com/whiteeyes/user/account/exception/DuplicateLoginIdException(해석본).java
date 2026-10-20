/*
 * [해석본] 원본: user/src/main/java/com/whiteeyes/user/account/exception/DuplicateLoginIdException.java (feature/30-signup 브랜치)
 * 관련 이슈: #30(FNC-U-002 예외 '아이디 중복: 409 DUPLICATE_LOGIN_ID')
 *
 * [무엇] 같은 아이디의 계정이 이미 있을 때 서비스가 던지는 예외. 탈퇴 계정도 포함한다(auth-005).
 * [위치] 도메인 안 exception/ 패키지(package-structure 3-1).
 */
package com.whiteeyes.user.account.exception;

import com.whiteeyes.common.exception.BusinessException;
import com.whiteeyes.common.exception.ErrorCode;

/** 같은 login_id의 계정이 이미 있다(FNC-U-002). 탈퇴 계정도 포함한다. */
// [해석] BusinessException 주석대로 기능별 예외는 상속한다. #96 핸들러가 ErrorCode의 상태(409)와 code로 응답하고,
//        4xx라서 스택 없이 WARN 로그 한 줄만 남긴다(code-conventions 7-3).
public class DuplicateLoginIdException extends BusinessException {

    public DuplicateLoginIdException() {
        super(ErrorCode.DUPLICATE_LOGIN_ID);
    }

    // [해석] DB가 막은 경우 원래 예외(DataIntegrityViolationException)를 cause로 남긴다. 응답에는 나가지 않지만 원인을 잃지 않는다.
    //        SignupServiceTest도 cause로 'DB 유일 조건이 막았다'를 확인한다.
    /** DB 유일 조건 위반을 감쌀 때 쓴다. */
    public DuplicateLoginIdException(Throwable cause) {
        super(ErrorCode.DUPLICATE_LOGIN_ID, ErrorCode.DUPLICATE_LOGIN_ID.getMessage(), cause);
    }
}
