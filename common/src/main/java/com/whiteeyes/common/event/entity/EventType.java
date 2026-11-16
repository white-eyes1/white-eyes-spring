package com.whiteeyes.common.event.entity;

/** 이벤트 종류(conventions.md 코드 값 event.type). */
public enum EventType {
    /** 혼잡 단계 상승 */
    // 아래 값들은 DB에 이름 문자열로 저장되는 이벤트 종류다
    CONGESTION,
    /** 급증 */
    // 짧은 시간에 인원이 크게 늘어난 경우(증가율 기준)
    SURGE,
    /** 캠 이상 */
    // 가동 요청된 캠이 결과를 보내지 않는 등 캠 자체에 문제가 있는 경우(캠 이상 감지)
    CAM_FAULT
}
