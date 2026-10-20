/*
 * [해석본] 원본: common/src/main/java/com/whiteeyes/common/level/service/CongestionLevel.java
 * 관련 이슈: #19(FNC-C-025)
 *
 * [무엇] 혼잡 단계 세 개(정상·주의·위험)와 각 단계의 번호·이름·색.
 * [왜 enum인가] 명세가 "단계 수·이름·색은 전체 공통으로 고정"(requirements.md evthres-001, conventions.md "단계는 코드에 고정")이라
 *   설정이 아니라 코드 상수다. 숫자 1·2·3이나 "#F59E0B" 같은 문자열을 여기저기 쓰면 하나만 바뀌어도 화면끼리 색이 어긋나므로,
 *   이 enum 한 곳에만 두고 모두 여기서 꺼내 쓴다.
 * [염두] 경계 밀도(0.7·1.5)는 여기 두지 않는다. 그 값은 환경변수로 바꿀 수 있는 '설정'이라 LevelService가 읽는다.
 *   '무엇이 단계인가'(고정)와 '어디서 단계가 바뀌는가'(설정)를 나눈 것이다.
 */
package com.whiteeyes.common.level.service;

/**
 * 혼잡 단계(conventions.md 코드 값 '혼잡 단계', decisions/0006). 단계 수·이름·색은 전체 공통으로 코드에 고정한다(evthres-001).
 */
// [해석] 상수 이름은 영어(NORMAL·CAUTION·DANGER)로 두고, 화면에 보이는 한국어 이름은 displayName에 따로 둔다.
//        decisions/0006에서 4단계 → 3단계로 바뀐 것처럼 이름이 바뀌어도 코드의 상수 이름은 그대로 둘 수 있다.
public enum CongestionLevel {
    NORMAL(1, "정상", "#3BC99B"),
    CAUTION(2, "주의", "#F59E0B"),
    DANGER(3, "위험", "#EF4D55");

    // [해석] level: DB(event.peak_level TINYINT)와 API(level)에 쓰는 숫자. enum의 순서(ordinal, 0부터)를 쓰지 않고 값을
    //        직접 적은 이유: 나중에 상수 순서를 바꾸거나 끼워 넣어도 저장된 숫자의 뜻이 바뀌지 않게 하려는 것.
    private final int level;
    // [해석] 'name'이라고 하지 않은 이유: enum에는 이미 name() 메서드(상수 이름 "NORMAL"을 돌려줌)가 있어 헷갈린다.
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
