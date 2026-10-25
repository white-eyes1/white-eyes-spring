package com.whiteeyes.common.event.entity;

/** 이벤트 조치 항목(conventions.md 코드 값 event.action_code). 조치완료로 바꿀 때 하나를 고른다(FNC-A-038). */
public enum EventActionCode {
    /** 현장 확인·정상 */
    ONSITE_OK,
    /** 인력 배치 */
    STAFF,
    /** 오탐 */
    FALSE_ALARM,
    /** 캠 점검 요청 */
    CAM_CHECK,
    /** 기타. 메모가 필수다 */
    OTHER
}
