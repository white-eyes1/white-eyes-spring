package com.whiteeyes.common.camera.entity;

/** 분석 서버가 보고한 캠의 실제 상태. */
public enum CameraStatus {
    // 정지
    STOPPED,
    // 가동을 시작하는 중(준비 진행률 표시)
    PREPARING,
    // 가동 중
    RUNNING,
    // 오류. 원인은 camera.error_message에 있다
    ERROR
}
