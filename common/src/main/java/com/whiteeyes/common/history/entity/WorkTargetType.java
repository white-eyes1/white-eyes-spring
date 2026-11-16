package com.whiteeyes.common.history.entity;

/** 기록 대상 종류(conventions.md 코드 값 work_history.target_type). */
public enum WorkTargetType {
    // 사용자 계정
    USER_ACCOUNT,
    // 관리자 계정
    ADMIN_ACCOUNT,
    // 구역
    ZONE,
    // 캠
    CAMERA,
    // 이벤트
    EVENT,
    // 지역(동·구·시)
    REGION,
    // 특정 대상 없이 전체를 다룬 경우(예: 전체 데이터 다운로드)
    ALL
}
