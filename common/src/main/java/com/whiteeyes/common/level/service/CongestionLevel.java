package com.whiteeyes.common.level.service;

/**
 * 혼잡 단계(conventions.md 코드 값 '혼잡 단계', decisions/0006). 단계 수·이름·색은 전체 공통으로 코드에 고정한다(evthres-001).
 */
public enum CongestionLevel {
    // 단계 번호 · 표시 이름 · 색. 번호는 DB(event.peak_level 등)에 저장되므로 바꾸면 기존 데이터와 어긋난다
    NORMAL(1, "정상", "#3BC99B"),
    CAUTION(2, "주의", "#F59E0B"),
    DANGER(3, "위험", "#EF4D55");

    // 단계 번호
    private final int level;
    // 화면에 보이는 이름
    private final String displayName;
    // 지도·범례에 칠하는 색
    private final String color;

    // enum 상수 선언의 인자를 필드에 담는다
    CongestionLevel(int level, String displayName, String color) {
        this.level = level;
        this.displayName = displayName;
        this.color = color;
    }

    /** 단계 번호(1~3). event.peak_level 등에 저장하는 값. */
    public int getLevel() {
        return level;
    }

    // 아래 두 getter는 설명이 필요 없는 단순 접근자다
    public String getDisplayName() {
        return displayName;
    }

    public String getColor() {
        return color;
    }
}
