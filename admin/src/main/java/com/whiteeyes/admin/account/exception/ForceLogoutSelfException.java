package com.whiteeyes.admin.account.exception;

import com.whiteeyes.common.exception.BusinessException;
import com.whiteeyes.common.exception.ErrorCode;

// 명세(public.md)가 '400 자기 자신'만 정하고 코드 이름이 없어 공통 INVALID_INPUT을 쓴다
/** 관리자가 자기 자신을 강제 로그아웃하려 할 때 던진다(400 INVALID_INPUT). 스스로 쫓겨나는 일을 막는다. */
public class ForceLogoutSelfException extends BusinessException {

    public ForceLogoutSelfException() {
        super(ErrorCode.INVALID_INPUT, "자기 자신은 로그아웃시킬 수 없습니다.");
    }
}
