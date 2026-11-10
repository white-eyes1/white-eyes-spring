package com.whiteeyes.admin.account.exception;

import com.whiteeyes.common.exception.BusinessException;
import com.whiteeyes.common.exception.ErrorCode;

// 명세(public.md)가 관리자 없음 코드를 따로 두지 않아 공통 NOT_FOUND를 쓴다(UserNotFoundException과 같음)
public class AdminNotFoundException extends BusinessException {

    public AdminNotFoundException(Long adminId) {
        super(ErrorCode.NOT_FOUND, "관리자를 찾을 수 없습니다. adminId=" + adminId);
    }
}
