package com.whiteeyes.common.event.entity;

/** 이벤트 처리 상태(conventions.md 코드 값 event.status). 앞으로만 바꿀 수 있다. */
public enum EventStatus {
    /** 미확인 */
    UNCHECKED,
    /** 확인 중 */
    CHECKED,
    /** 조치완료 */
    RESOLVED;

    /** 앞으로만 바꿀 수 있다: 미확인→확인 중·조치완료, 확인 중→조치완료(FNC-A-038). 같은 상태나 되돌리기는 안 된다. */
    public boolean canChangeTo(EventStatus next) {
        return next.ordinal() > ordinal();
    }
}
