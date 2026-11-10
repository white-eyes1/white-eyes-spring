package com.whiteeyes.admin.event.exception;

import com.whiteeyes.common.exception.BusinessException;
import com.whiteeyes.common.exception.ErrorCode;

/** 처리 상태를 바꿀 수 없을 때(FNC-A-038): 다른 관리자가 먼저 처리했거나, 앞으로 가는 변경이 아니다. 409 STATUS_CONFLICT. */
public class EventStatusConflictException extends BusinessException {

    private EventStatusConflictException(String message) {
        super(ErrorCode.STATUS_CONFLICT, message);
    }

    /** 화면이 보던 상태와 서버의 현재 상태가 다르다. 메시지는 화면에 그대로 보인다(screens.md SCR-A-010). */
    public static EventStatusConflictException changedByOther() {
        return new EventStatusConflictException("다른 관리자가 먼저 처리했습니다.");
    }

    /** 같은 상태로 바꾸거나 되돌리려는 요청. */
    public static EventStatusConflictException notForward() {
        return new EventStatusConflictException("처리 상태는 앞으로만 바꿀 수 있습니다.");
    }
}
