package com.whiteeyes.admin.demo.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

/** 시연용 통계 생성 작업(FNC-A-051)의 --zone 옵션 읽기 */
class DemoGenerateJobRunnerOptionTest {

    @Test
    @DisplayName("FNC-A-051 --zone은 쉼표로 잇거나 여러 번 줄 수 있고, 없으면 모든 구역(null)")
    void readsZoneOption() {
        assertThat(DemoGenerateJobRunner.zoneIds(args("--job=generate-demo"))).isNull();
        assertThat(DemoGenerateJobRunner.zoneIds(args("--zone=1, 2"))).containsExactly(1L, 2L);
        assertThat(DemoGenerateJobRunner.zoneIds(args("--zone=3", "--zone=4"))).containsExactly(3L, 4L);
    }

    @Test
    @DisplayName("FNC-A-051 --zone에 숫자가 아닌 값이 있으면 멈춘다")
    void rejectsInvalidZoneOption() {
        assertThatThrownBy(() -> DemoGenerateJobRunner.zoneIds(args("--zone=1,a")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DemoGenerateJobRunner.zoneIds(args("--zone=")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static DefaultApplicationArguments args(String... values) {
        return new DefaultApplicationArguments(values);
    }
}
