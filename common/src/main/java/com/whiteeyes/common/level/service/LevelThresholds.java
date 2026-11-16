package com.whiteeyes.common.level.service;

import java.math.BigDecimal;

/**
 * 단계 경계 밀도(명/㎡). 필드 이름은 이벤트에 저장하는 threshold_snapshot {warnDensity, dangerDensity}와 같다(FNC-A-033).
 *
 * @param warnDensity '주의'가 시작되는 밀집도
 * @param dangerDensity '위험'이 시작되는 밀집도
 */
public record LevelThresholds(BigDecimal warnDensity, BigDecimal dangerDensity) {

    /**
     * 밀집도를 단계로 바꾼다. 경계값과 같은 밀집도는 높은 단계로 판정한다(FNC-C-025 검수 기준).
     * 비교는 compareTo로 해서 0.7과 0.7000을 같은 값으로 본다.
     */
    public CongestionLevel levelOf(BigDecimal density) {
        // 높은 단계부터 검사한다. 위험 경계 이상이면 주의 조건도 만족하지만 더 높은 단계가 이겨야 하기 때문이다
        if (density.compareTo(dangerDensity) >= 0) {
            return CongestionLevel.DANGER;
        }
        // 위험이 아니고 주의 경계 이상이면 주의
        if (density.compareTo(warnDensity) >= 0) {
            return CongestionLevel.CAUTION;
        }
        // 두 경계 모두 못 넘으면 정상
        return CongestionLevel.NORMAL;
    }

    /** 그 단계가 시작되는 밀집도. 정상은 0. */
    public BigDecimal minDensityOf(CongestionLevel level) {
        // 단계마다 그 단계가 시작되는 밀도를 돌려준다. 정상은 아래쪽 한계가 없어 0이다
        return switch (level) {
            case NORMAL -> BigDecimal.ZERO;
            case CAUTION -> warnDensity;
            case DANGER -> dangerDensity;
        };
    }
}
