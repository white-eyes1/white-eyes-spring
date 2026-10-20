package com.whiteeyes.common.level.service;

/**
 * 혼잡 단계(conventions.md 코드 값 '혼잡 단계', decisions/0006). 단계 수·이름·색은 전체 공통으로 코드에 고정한다(evthres-001).
 */
public enum CongestionLevel {
    NORMAL(1, "정상", "#3BC99B"),
    CAUTION(2, "주의", "#F59E0B"),
    DANGER(3, "위험", "#EF4D55");

    private final int level;
    private final String displayName;
    private final String color;

    CongestionLevel(int level, String displayName, String color) {
        this.level = level;
        this.displayName = displayName;
        this.color = color;
    }

    /** 단계 번호(1~3). event.peak_level 등에 저장하는 값. */
    public int getLevel() {
        return level;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getColor() {
        return color;
    }
}
