package com.whiteeyes.common.stat.dto;

/**
 * 대시보드·기간 비교 대상의 종류(targetType). 구역 하나 또는 지역(시·도, 시·군·구, 읍·면·동).
 */
public enum DashboardTargetType {
    // 구역 하나
    ZONE,
    // 지역(시·도, 시·군·구, 읍·면·동). 아래 구역을 모두 합쳐서 본다
    REGION
}
