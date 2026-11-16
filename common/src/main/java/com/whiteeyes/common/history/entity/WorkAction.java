package com.whiteeyes.common.history.entity;

/** 기록 대상 행동 종류(conventions.md 코드 값 work_history.action). */
public enum WorkAction {
    // 아래 값들은 DB에 이름 문자열로 저장된다. 엑셀 다운로드
    EXCEL_DOWNLOAD,
    // 이벤트 처리 상태 변경
    EVENT_STATUS,
    // 계정 정보·상태 변경(내 정보 수정, 승인 등)
    ACCOUNT_CHANGE,
    // 캠 가동·정지
    CAMERA_RUN,
    // 구역 등록·수정·삭제
    ZONE_CHANGE,
    // 캠 등록·수정·삭제
    CAMERA_CHANGE
}
