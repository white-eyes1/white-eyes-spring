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

    @Test
    @DisplayName("FNC-C-025 경계값과 같은 밀집도는 높은 단계로 판정")
    void judgeLevel_densityEqualToThreshold_higherLevel() {
        assertThat(levelService.judgeLevel(ZONE, new BigDecimal("0.7"))).isEqualTo(CongestionLevel.CAUTION);
        assertThat(levelService.judgeLevel(ZONE, new BigDecimal("1.5"))).isEqualTo(CongestionLevel.DANGER);
    }

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

    @Test
    @DisplayName("FNC-C-025 구역과 지역은 지금 같은 공통 기준을 쓴다")
    void findThresholds_zoneAndRegion_sameCommonThresholds() {
        LevelThresholds zoneThresholds = levelService.findThresholds(LevelTarget.zone(3L));
        LevelThresholds regionThresholds = levelService.findThresholds(LevelTarget.region(15L));

        assertThat(zoneThresholds).isEqualTo(regionThresholds);
        assertThat(zoneThresholds.warnDensity()).isEqualByComparingTo("0.7");
        assertThat(zoneThresholds.dangerDensity()).isEqualByComparingTo("1.5");
    }

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

    @Test
    @DisplayName("FNC-C-025 주의 기준이 0 이하면 시작하지 않는다")
    void constructor_warnNotPositive_throws() {
        assertThatThrownBy(() -> new LevelService(BigDecimal.ZERO, new BigDecimal("1.5")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("THRESHOLD_WARN_DENSITY(0)");
    }
}
