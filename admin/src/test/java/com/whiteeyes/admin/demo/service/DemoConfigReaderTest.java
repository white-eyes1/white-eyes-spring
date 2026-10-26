package com.whiteeyes.admin.demo.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 시연용 통계 생성 설정 읽기(FNC-A-051) */
class DemoConfigReaderTest {

    // 테스트는 admin 모듈 폴더에서 돈다
    private static final Path EXAMPLE = Paths.get("demo/generate-demo.example.json");

    @Test
    @DisplayName("FNC-A-051 저장소의 설정 견본을 그대로 읽을 수 있다")
    void readsExampleConfig() {
        DemoGenerateConfig config = new DemoConfigReader().read(EXAMPLE);

        assertThat(config.hourWeights()).hasSize(24);
        assertThat(config.dayOfWeekWeights()).hasSize(7);
        assertThat(config.seasonWeights()).hasSize(4);
        assertThat(config.defaultZone()).isNotNull();
    }
}
