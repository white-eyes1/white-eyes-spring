package com.whiteeyes.admin.account.exception;

import com.whiteeyes.common.exception.BusinessException;
import com.whiteeyes.common.exception.ErrorCode;

// 명세(public.md)가 '400 최고 관리자 대상 불가'만 정하고 코드 이름이 없어 공통 INVALID_INPUT을 쓴다
public class SuperAdminTargetException extends BusinessException {

    public SuperAdminTargetException() {
        super(ErrorCode.INVALID_INPUT, "최고 관리자 계정은 비활성화할 수 없습니다.");
    }
}
