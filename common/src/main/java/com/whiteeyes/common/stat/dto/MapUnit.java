package com.whiteeyes.common.stat.dto;

/**
 * 지도 현황의 단위(GET /api/v1/map/status의 unit).
 */
public enum MapUnit {
    /** 바로 아래 단위. 읍·면·동이면 구역. */
    CHILD,
    /** 대상 아래의 모든 구역. */
    ZONE
}
