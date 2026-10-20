/*
 * [해석본] 원본: common/src/test/java/com/whiteeyes/common/level/service/LevelServiceTest.java
 * 관련 이슈: #19(FNC-C-025)
 *
 * [무엇] 혼잡 단계 판정의 단위 테스트. 이슈 #19의 완료 기준 "경계값과 같은 밀집도는 높은 단계로 판정"이 여기서 확인된다.
 * [왜 Spring 없이] LevelService는 숫자 두 개만 받으면 만들어지는 객체라 new로 직접 만든다. 빠르고, 기준값을 원하는 대로
 *   넣어 잘못된 설정(시작 실패)도 확인할 수 있다.
 * [메서드 이름] common 모듈의 기존 테스트(PasswordEncoderConfigTest)가 '대상_조건_결과' 형식이라 같은 형식을 따랐다.
 *   설명은 @DisplayName에 기능 ID와 함께 한국어로 쓴다(code-conventions 6장).
 */
package com.whiteeyes.common.level.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class LevelServiceTest {

    private static final LevelTarget ZONE = LevelTarget.zone(3L);

    // conventions.md 기본값
    private final LevelService levelService = new LevelService(new BigDecimal("0.7"), new BigDecimal("1.5"));

    // [해석] 완료 기준 그대로. 경계값 두 개(0.7, 1.5)가 각각 높은 단계(주의, 위험)로 판정되는지 본다.
    //        >= 대신 >로 잘못 쓰면 이 테스트가 깨진다.
    @Test
    @DisplayName("FNC-C-025 경계값과 같은 밀집도는 높은 단계로 판정")
    void judgeLevel_densityEqualToThreshold_higherLevel() {
        assertThat(levelService.judgeLevel(ZONE, new BigDecimal("0.7"))).isEqualTo(CongestionLevel.CAUTION);
        assertThat(levelService.judgeLevel(ZONE, new BigDecimal("1.5"))).isEqualTo(CongestionLevel.DANGER);
    }

    // [해석] 경계 근처를 표로 한꺼번에 확인한다(@ParameterizedTest: 한 테스트를 여러 입력으로 반복 실행).
    //        - 0.6999·1.4999: 경계 바로 아래(DB density의 소수 4자리 기준 가장 가까운 값)는 낮은 단계
    //        - 0.7000·1.5000: DB에서 읽은 값처럼 자릿수가 달라도 같은 값이면 같은 단계(compareTo로 비교하는지 확인)
    //        - 0, 3.2: 양 끝
    //        입력을 String으로 받아 new BigDecimal(density)로 만든다. double로 받으면 0.7이 정확히 들어오지 않는다.
    @ParameterizedTest(name = "밀집도 {0} → {1}")
    @CsvSource({
        "0, NORMAL",
        "0.6999, NORMAL",
        "0.7000, CAUTION",
        "1.4999, CAUTION",
        "1.5000, DANGER",
        "3.2, DANGER"
    })
    @DisplayName("FNC-C-025 경계 바로 아래는 낮은 단계, 자릿수가 달라도 같은 값은 같은 단계")
    void judgeLevel_aroundThresholds_expectedLevel(String density, CongestionLevel expected) {
        assertThat(levelService.judgeLevel(ZONE, new BigDecimal(density))).isEqualTo(expected);
    }

    // [해석] 결정 1의 '지금은 항상 공통 기준'을 확인한다. 2순위 기능으로 구역별 기준이 들어오면 이 테스트를 고쳐야 한다
    //        (그때가 되면 이 테스트가 깨져서 바뀐 동작을 알려 준다).
    @Test
    @DisplayName("FNC-C-025 구역과 지역은 지금 같은 공통 기준을 쓴다")
    void findThresholds_zoneAndRegion_sameCommonThresholds() {
        LevelThresholds zoneThresholds = levelService.findThresholds(LevelTarget.zone(3L));
        LevelThresholds regionThresholds = levelService.findThresholds(LevelTarget.region(15L));

        assertThat(zoneThresholds).isEqualTo(regionThresholds);
        assertThat(zoneThresholds.warnDensity()).isEqualByComparingTo("0.7");
        assertThat(zoneThresholds.dangerDensity()).isEqualByComparingTo("1.5");
    }

    // [해석] API가 내보낼 값의 순서·이름·색·시작 밀집도. 이름과 색은 conventions.md의 값을 그대로 적었다.
    //        minDensity는 BigDecimal이라 '값이 같은지'로 비교하도록 비교 방법(compareTo)을 직접 넘겼다.
    //        기본 비교(equals)는 0.7과 0.70을 다르다고 본다.
    @Test
    @DisplayName("FNC-C-025 단계 기준은 정상·주의·위험 순서로 이름·색·시작 밀집도를 준다")
    void findLevelCriteria_threeLevels_namesColorsAndMinDensities() {
        assertThat(levelService.findLevelCriteria())
                .extracting("level", "name", "color")
                .containsExactly(tuple(1, "정상", "#3BC99B"), tuple(2, "주의", "#F59E0B"), tuple(3, "위험", "#EF4D55"));
        assertThat(levelService.findLevelCriteria())
                .extracting("minDensity")
                .usingElementComparator((left, right) -> ((BigDecimal) left).compareTo((BigDecimal) right))
                .containsExactly(BigDecimal.ZERO, new BigDecimal("0.7"), new BigDecimal("1.5"));
    }

    // [해석] 결정 2의 시작 검사. 주의 2.0 > 위험 1.5(순서 뒤집힘)와 주의 = 위험(1.5, 1.5) 둘 다 막아야 한다.
    //        메시지에 변수 이름과 값이 들어가는지도 본다. 배포한 사람이 로그만 보고 무엇을 고칠지 알 수 있어야 하기 때문.
    @Test
    @DisplayName("FNC-C-025 주의 기준이 위험 기준보다 작지 않으면 시작하지 않는다")
    void constructor_warnNotBelowDanger_throws() {
        assertThatThrownBy(() -> new LevelService(new BigDecimal("2.0"), new BigDecimal("1.5")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("THRESHOLD_WARN_DENSITY(2.0)")
                .hasMessageContaining("THRESHOLD_DANGER_DENSITY(1.5)");
        assertThatThrownBy(() -> new LevelService(new BigDecimal("1.5"), new BigDecimal("1.5")))
                .isInstanceOf(IllegalStateException.class);
    }

    // [해석] 주의 기준 0이면 아무도 없는 구역(밀집도 0)도 '주의'가 된다. 0과 음수를 막는 검사의 경계(0)를 확인한다.
    @Test
    @DisplayName("FNC-C-025 주의 기준이 0 이하면 시작하지 않는다")
    void constructor_warnNotPositive_throws() {
        assertThatThrownBy(() -> new LevelService(BigDecimal.ZERO, new BigDecimal("1.5")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("THRESHOLD_WARN_DENSITY(0)");
    }
}
