package com.whiteeyes.common.event.entity;

/** 이벤트 종류(conventions.md 코드 값 event.type). */
public enum EventType {
    /** 혼잡 단계 상승 */
    CONGESTION,
    /** 급증 */
    SURGE,
    /** 캠 이상 */
    CAM_FAULT
}
