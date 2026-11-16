package com.whiteeyes.admin.account.exception;

import com.whiteeyes.common.exception.BusinessException;
import com.whiteeyes.common.exception.ErrorCode;

// 명세(public.md)가 관리자 없음 코드를 따로 두지 않아 공통 NOT_FOUND를 쓴다(UserNotFoundException과 같음)
/** 요청한 관리자 번호의 계정이 없을 때 던진다(404 NOT_FOUND). */
public class AdminNotFoundException extends BusinessException {

    public AdminNotFoundException(Long adminId) {
        // 공통 NOT_FOUND 코드를 쓰고, 문구에 번호를 넣어 어느 계정을 못 찾았는지 알 수 있게 한다
        super(ErrorCode.NOT_FOUND, "관리자를 찾을 수 없습니다. adminId=" + adminId);
    }
}
