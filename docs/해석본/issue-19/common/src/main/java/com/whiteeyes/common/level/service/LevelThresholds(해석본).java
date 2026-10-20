/*
 * [해석본] 원본: common/src/main/java/com/whiteeyes/common/level/service/LevelThresholds.java
 * 관련 이슈: #19(FNC-C-025)
 *
 * [무엇] 단계가 바뀌는 경계 밀도 두 개(주의 시작·위험 시작)와, 그 경계로 밀집도를 단계로 바꾸는 판정.
 * [이름을 이렇게 지은 이유] 혼잡 이벤트(FNC-A-033, #38)는 이벤트가 생길 때 당시 기준을
 *   threshold_snapshot = {warnDensity, dangerDensity}로 저장한다(tables.md event, decisions/0006 '영향').
 *   필드 이름을 똑같이 맞춰 두면 #38이 이 객체를 그대로 JSON으로 바꿔 저장할 수 있다.
 * [판정 규칙] 검수 기준 "경계값과 같은 밀집도는 높은 단계로 판정". 그래서 '크거나 같으면'(>=)으로 비교한다.
 *   밀집도 0.7이면 '주의', 1.5면 '위험'.
 */
package com.whiteeyes.common.level.service;

import java.math.BigDecimal;

/**
 * 단계 경계 밀도(명/㎡). 필드 이름은 이벤트에 저장하는 threshold_snapshot {warnDensity, dangerDensity}와 같다(FNC-A-033).
 *
 * @param warnDensity '주의'가 시작되는 밀집도
 * @param dangerDensity '위험'이 시작되는 밀집도
 */
// [해석] BigDecimal을 쓴 이유(사용자와 합의한 결정 3): double은 0.7을 정확히 담지 못한다(0.6999999…).
//        지역 밀집도를 나눗셈으로 구하면 경계값 0.7이 0.69999…로 나와 '주의'가 '정상'이 될 수 있다.
//        DB의 density도 DECIMAL이라 #18의 ZoneStatMinute.getDensity()를 바꾸지 않고 그대로 넘길 수 있다.
public record LevelThresholds(BigDecimal warnDensity, BigDecimal dangerDensity) {

    /**
     * 밀집도를 단계로 바꾼다. 경계값과 같은 밀집도는 높은 단계로 판정한다(FNC-C-025 검수 기준).
     * 비교는 compareTo로 해서 0.7과 0.7000을 같은 값으로 본다.
     */
    // [해석] 높은 단계부터 검사한다. 위험 경계를 넘었으면 주의 경계도 당연히 넘었으므로, 순서를 반대로 하면
    //        1.8도 '주의'로 끝나 버린다.
    //        compareTo(...) >= 0 은 "density ≥ 경계"라는 뜻. BigDecimal에는 >= 연산자를 쓸 수 없어서 이렇게 쓴다.
    //        equals를 쓰지 않는 이유: BigDecimal.equals는 자릿수까지 비교해서 0.7과 0.7000(DB에서 읽은 값)을 다르다고 본다.
    public CongestionLevel levelOf(BigDecimal density) {
        if (density.compareTo(dangerDensity) >= 0) {
            return CongestionLevel.DANGER;
        }
        if (density.compareTo(warnDensity) >= 0) {
            return CongestionLevel.CAUTION;
        }
        return CongestionLevel.NORMAL;
    }

    /** 그 단계가 시작되는 밀집도. 정상은 0. */
    // [해석] API 응답의 minDensity(api/public.md: 정상 0, 주의 0.7, 위험 1.5)를 만들 때 쓴다.
    //        switch 식(->): 세 단계를 모두 다루지 않으면 컴파일 오류가 나서, 나중에 단계가 늘어도 빠뜨릴 수 없다.
    public BigDecimal minDensityOf(CongestionLevel level) {
        return switch (level) {
            case NORMAL -> BigDecimal.ZERO;
            case CAUTION -> warnDensity;
            case DANGER -> dangerDensity;
        };
    }
}
