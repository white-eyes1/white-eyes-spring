package com.whiteeyes.admin.account.exception;

import com.whiteeyes.common.account.entity.UserAccountStatus;
import com.whiteeyes.common.exception.BusinessException;
import com.whiteeyes.common.exception.ErrorCode;

public class InvalidUserStatusException extends BusinessException {

    public InvalidUserStatusException(Long userId, UserAccountStatus status) {
        this(userId, status, "승인");
    }

    /** @param action 하려던 일(예: 승인, 승인 취소). 로그 문구에만 쓴다 */
    public InvalidUserStatusException(Long userId, UserAccountStatus status, String action) {
        super(ErrorCode.INVALID_STATUS, "현재 상태(" + status + ")에서는 " + action + "할 수 없습니다. userId=" + userId);
    }
}
