package com.whiteeyes.admin.account.exception;

import com.whiteeyes.admin.account.entity.AdminStatus;
import com.whiteeyes.common.exception.BusinessException;
import com.whiteeyes.common.exception.ErrorCode;

public class InvalidAdminStatusException extends BusinessException {

    /** @param action 하려던 일('비활성화' 또는 '활성화') */
    public InvalidAdminStatusException(Long adminId, AdminStatus status, String action) {
        super(ErrorCode.INVALID_STATUS, "현재 상태(" + status + ")에서는 " + action + "할 수 없습니다. adminId=" + adminId);
    }
}
