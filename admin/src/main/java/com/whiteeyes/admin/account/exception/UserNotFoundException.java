package com.whiteeyes.admin.account.exception;

import com.whiteeyes.common.exception.BusinessException;
import com.whiteeyes.common.exception.ErrorCode;

// 명세(public.md)가 사용자 없음 코드를 따로 두지 않아 공통 NOT_FOUND를 쓴다
/** 요청한 사용자 번호의 계정이 없을 때 던진다(404 NOT_FOUND). */
public class UserNotFoundException extends BusinessException {

    public UserNotFoundException(Long userId) {
        super(ErrorCode.NOT_FOUND, "사용자를 찾을 수 없습니다. userId=" + userId);
    }
}
