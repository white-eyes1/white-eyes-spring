package com.whiteeyes.admin.account.exception;

import com.whiteeyes.common.exception.BusinessException;
import com.whiteeyes.common.exception.ErrorCode;

/** 같은 login_id의 관리자 계정이 이미 있다(FNC-A-009, 409 DUPLICATE_LOGIN_ID). 사용자 계정 아이디와는 별개로 본다. */
public class DuplicateAdminLoginIdException extends BusinessException {

    public DuplicateAdminLoginIdException() {
        super(ErrorCode.DUPLICATE_LOGIN_ID);
    }

    /** DB 유일 조건 위반을 감쌀 때 쓴다. */
    public DuplicateAdminLoginIdException(Throwable cause) {
        super(ErrorCode.DUPLICATE_LOGIN_ID, ErrorCode.DUPLICATE_LOGIN_ID.getMessage(), cause);
    }
}
