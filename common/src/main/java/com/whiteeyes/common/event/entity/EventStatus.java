package com.whiteeyes.common.event.entity;

/** 이벤트 처리 상태(conventions.md 코드 값 event.status). 앞으로만 바꿀 수 있다. */
public enum EventStatus {
    /** 미확인 */
    UNCHECKED,
    /** 확인 중 */
    CHECKED,
    /** 조치완료 */
    RESOLVED
}
