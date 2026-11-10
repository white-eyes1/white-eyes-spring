package com.whiteeyes.admin.account.exception;

import com.whiteeyes.common.exception.BusinessException;
import com.whiteeyes.common.exception.ErrorCode;

// 명세(public.md)가 '400 최고 관리자 대상 불가'만 정하고 코드 이름이 없어 공통 INVALID_INPUT을 쓴다.
// 비활성화(FNC-A-011)는 명세가 정했고, 정보 수정·비밀번호 재설정(FNC-A-010)은 대상을 role=ADMIN으로만 적어 같은 400으로 맞췄다
public class SuperAdminTargetException extends BusinessException {

    /** @param action 막힌 동작 이름(예: 비활성화, 정보 수정, 비밀번호 재설정) */
    public SuperAdminTargetException(String action) {
        super(ErrorCode.INVALID_INPUT, "최고 관리자 계정은 " + action + "할 수 없습니다.");
    }
}
