package com.whiteeyes.common.camera.entity;

/** 캠의 입력 방식. 분석 서버가 실시간 분석(LIVE)과 캐시 재생(DUMMY) 중 고른다. */
public enum CameraSourceType {
    LIVE,
    DUMMY
}
