package com.whiteeyes.admin.event.exception;

import com.whiteeyes.common.exception.BusinessException;
import com.whiteeyes.common.exception.ErrorCode;

// 명세(FNC-A-037)가 없는 이벤트를 404로만 정하고 전용 코드를 두지 않아 공통 NOT_FOUND를 쓴다
/** 요청한 번호의 이벤트가 없을 때 던진다(404 NOT_FOUND). */
public class EventNotFoundException extends BusinessException {

    public EventNotFoundException(Long eventId) {
        super(ErrorCode.NOT_FOUND, "이벤트를 찾을 수 없습니다. eventId=" + eventId);
    }
}
