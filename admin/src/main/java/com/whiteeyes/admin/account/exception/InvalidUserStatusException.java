package com.whiteeyes.admin.account.exception;

import com.whiteeyes.common.account.entity.UserAccountStatus;
import com.whiteeyes.common.exception.BusinessException;
import com.whiteeyes.common.exception.ErrorCode;

/** 현재 상태에서는 할 수 없는 사용자 처리(승인·승인 취소·재설정·수정)를 요청했을 때 던진다(409 INVALID_STATUS). */
public class InvalidUserStatusException extends BusinessException {

    /** 하려던 일을 따로 말하지 않으면 '승인'으로 본다(승인 API가 쓰는 기본 형태). */
    public InvalidUserStatusException(Long userId, UserAccountStatus status) {
        this(userId, status, "승인");
    }

    /** @param action 하려던 일(예: 승인, 승인 취소). 로그 문구에만 쓴다 */
    public InvalidUserStatusException(Long userId, UserAccountStatus status, String action) {
        super(ErrorCode.INVALID_STATUS, "현재 상태(" + status + ")에서는 " + action + "할 수 없습니다. userId=" + userId);
    }
}
