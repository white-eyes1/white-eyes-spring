package com.whiteeyes.user.account.exception;

import com.whiteeyes.common.exception.BusinessException;
import com.whiteeyes.common.exception.ErrorCode;

/** 같은 login_id의 계정이 이미 있다(FNC-U-002). 탈퇴 계정도 포함한다. */
public class DuplicateLoginIdException extends BusinessException {

    /**
     * 가입 전에 이미 같은 아이디가 있는 것을 확인했을 때 쓴다.
     */
    public DuplicateLoginIdException() {
        // 응답 상태·문구는 ErrorCode.DUPLICATE_LOGIN_ID가 정하고, 공통 예외 처리기가 그대로 응답으로 바꾼다.
        super(ErrorCode.DUPLICATE_LOGIN_ID);
    }

    /** DB 유일 조건 위반을 감쌀 때 쓴다. */
    public DuplicateLoginIdException(Throwable cause) {
        // 원인 예외를 함께 넘겨, 로그에서 원래 DB 오류를 따라갈 수 있게 한다.
        super(ErrorCode.DUPLICATE_LOGIN_ID, ErrorCode.DUPLICATE_LOGIN_ID.getMessage(), cause);
    }
}
