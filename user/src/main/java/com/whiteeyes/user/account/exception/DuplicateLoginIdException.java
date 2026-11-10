package com.whiteeyes.user.account.exception;

import com.whiteeyes.common.exception.BusinessException;
import com.whiteeyes.common.exception.ErrorCode;

/** 같은 login_id의 계정이 이미 있다(FNC-U-002). 탈퇴 계정도 포함한다. */
public class DuplicateLoginIdException extends BusinessException {

    public DuplicateLoginIdException() {
        super(ErrorCode.DUPLICATE_LOGIN_ID);
    }

    /** DB 유일 조건 위반을 감쌀 때 쓴다. */
    public DuplicateLoginIdException(Throwable cause) {
        super(ErrorCode.DUPLICATE_LOGIN_ID, ErrorCode.DUPLICATE_LOGIN_ID.getMessage(), cause);
    }
}
