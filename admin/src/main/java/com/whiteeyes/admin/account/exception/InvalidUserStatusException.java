package com.whiteeyes.admin.account.exception;

import com.whiteeyes.common.account.entity.UserAccountStatus;
import com.whiteeyes.common.exception.BusinessException;
import com.whiteeyes.common.exception.ErrorCode;

public class InvalidUserStatusException extends BusinessException {

    public InvalidUserStatusException(Long userId, UserAccountStatus status) {
        super(ErrorCode.INVALID_STATUS, "현재 상태(" + status + ")에서는 승인할 수 없습니다. userId=" + userId);
    }
}
