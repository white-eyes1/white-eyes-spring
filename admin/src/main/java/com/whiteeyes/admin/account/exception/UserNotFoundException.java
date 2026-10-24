package com.whiteeyes.admin.account.exception;

import com.whiteeyes.common.exception.BusinessException;
import com.whiteeyes.common.exception.ErrorCode;

// 명세(public.md)가 사용자 없음 코드를 따로 두지 않아 공통 NOT_FOUND를 쓴다
public class UserNotFoundException extends BusinessException {

    public UserNotFoundException(Long userId) {
        super(ErrorCode.NOT_FOUND, "사용자를 찾을 수 없습니다. userId=" + userId);
    }
}
