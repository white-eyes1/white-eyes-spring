package com.whiteeyes.admin.demo.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.DayOfWeek;
import java.time.LocalDate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 시연용 통계 생성 설정 읽기(FNC-A-051) */
class DemoConfigReaderTest {

    // 테스트는 admin 모듈 폴더에서 돈다
    private static final Path EXAMPLE = Paths.get("demo/generate-demo.example.json");

    private final DemoConfigReader reader = new DemoConfigReader();

    @TempDir
    private Path tempDir;

    @Test
    @DisplayName("FNC-A-051 저장소의 설정 견본을 그대로 읽을 수 있다")
    void readsExampleConfig() {
        DemoGenerateConfig config = reader.read(EXAMPLE);

        assertThat(config.hourWeights()).hasSize(24);
        assertThat(config.dayOfWeekWeights()).hasSize(7);
        assertThat(config.seasonWeights()).hasSize(4);
        assertThat(config.defaultZone()).isNotNull();
    }

    @Test
    @DisplayName("FNC-A-051 설정 파일이 없으면 코드의 기본값을 쓰고, 기본값은 검사를 통과한다")
    void defaultsAreValid() {
        DemoGenerateConfig defaults = reader.defaults();

        defaults.validate();
        assertThat(defaults.seed()).isNotNull();
        assertThat(defaults.zones()).isEmpty();
        assertThat(defaults.surgeDays()).isEmpty();
    }

    @Test
    @DisplayName("FNC-A-051 설정 파일에 적은 항목만 기본값 위에 덮어쓴다")
    void overridesOnlyGivenItems() throws IOException {
        Path file = write("""
                {"zones": [{"zoneId": 3, "baseCount": 80, "avgDwellSec": 900}],
                 "surgeDays": [{"date": "2026-05-05", "weight": 2.5}]}
                """);

        DemoGenerateConfig config = reader.read(file);
        DemoGenerateConfig defaults = reader.defaults();

        assertThat(config.zones()).singleElement().satisfies(zone -> assertThat(zone.baseCount()).isEqualTo(80.0));
        assertThat(config.surgeDays()).singleElement()
                .satisfies(day -> assertThat(day.date()).isEqualTo(LocalDate.of(2026, 5, 5)));
        assertThat(config.seed()).isEqualTo(defaults.seed());
        assertThat(config.defaultZone()).isEqualTo(defaults.defaultZone());
        assertThat(config.hourWeights()).isEqualTo(defaults.hourWeights());
        assertThat(config.dayOfWeekWeights().get(DayOfWeek.SATURDAY))
                .isEqualTo(defaults.dayOfWeekWeights().get(DayOfWeek.SATURDAY));
    }

    @Test
    @DisplayName("FNC-A-051 예전 설정 파일의 startDate는 멈추지 않고 무시한다")
    void ignoresStartDate() throws IOException {
        Path file = write("{\"seed\": 7, \"startDate\": \"2025-10-24\"}");

        assertThat(reader.read(file).seed()).isEqualTo(7L);
    }

    @Test
    @DisplayName("FNC-A-051 모르는 항목이나 틀린 값이 있으면 멈춘다")
    void rejectsUnknownOrInvalidItems() throws IOException {
        Path typo = write("{\"sead\": 7}");
        Path shortHours = write("{\"hourWeights\": [1, 1, 1]}");
        Path negativeBase = write("{\"defaultZone\": {\"baseCount\": -1, \"avgDwellSec\": 600}}");

        assertThatThrownBy(() -> reader.read(typo)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> reader.read(shortHours)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> reader.read(negativeBase)).isInstanceOf(IllegalArgumentException.class);
    }

    private Path write(String content) throws IOException {
        Path file = Files.createTempFile(tempDir, "generate-demo", ".json");
        Files.writeString(file, content);
        return file;
    }
}
