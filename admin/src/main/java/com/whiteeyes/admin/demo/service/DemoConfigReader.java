package com.whiteeyes.admin.demo.service;

import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.whiteeyes.admin.demo.service.DemoGenerateConfig.Season;
import com.whiteeyes.admin.demo.service.DemoGenerateConfig.SurgeDay;
import com.whiteeyes.admin.demo.service.DemoGenerateConfig.ZoneSetting;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * 시연용 통계 생성 설정을 만든다(FNC-A-051 입력 항목 --config). 설정 파일은 선택이다.
 * 파일이 없으면 기본값을 쓰고, 있으면 파일에 적은 항목만 기본값 위에 덮어쓴 뒤 검사한다.
 */
@Component
public class DemoConfigReader {

    private static final Logger log = LoggerFactory.getLogger(DemoConfigReader.class);

    // 철자가 틀린 항목이 조용히 기본값으로 바뀌지 않게 모르는 항목이 있으면 멈춘다
    private final JsonMapper jsonMapper = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    /** 설정 파일 없이 쓰는 기본 설정. */
    public DemoGenerateConfig defaults() {
        return DemoGenerateConfig.defaults();
    }

    /**
     * @throws IllegalArgumentException 파일을 읽을 수 없거나 설정이 틀렸을 때
     */
    public DemoGenerateConfig read(Path file) {
        ConfigFile values;
        try {
            values = jsonMapper.readValue(file.toFile(), ConfigFile.class);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("시연용 통계 설정 파일을 읽을 수 없습니다: " + file, e);
        }
        if (values.startDate() != null) {
            // 예전 설정 파일에 남아 있어도 멈추지 않는다. 시작일은 구역마다 끝 시각에서 정한다
            log.warn("시연용 통계 설정의 startDate는 쓰지 않아 무시함 startDate={}", values.startDate());
        }
        DemoGenerateConfig defaults = DemoGenerateConfig.defaults();
        DemoGenerateConfig config = new DemoGenerateConfig(
                orDefault(values.seed(), defaults.seed()),
                orDefault(values.defaultZone(), defaults.defaultZone()),
                orDefault(values.zones(), defaults.zones()),
                orDefault(values.hourWeights(), defaults.hourWeights()),
                orDefault(values.dayOfWeekWeights(), defaults.dayOfWeekWeights()),
                orDefault(values.seasonWeights(), defaults.seasonWeights()),
                orDefault(values.surgeDays(), defaults.surgeDays()));
        config.validate();
        return config;
    }

    private static <T> T orDefault(T value, T defaultValue) {
        return value == null ? defaultValue : value;
    }

    /** 설정 파일의 내용. 적지 않은 항목은 null이고 기본값을 쓴다. startDate는 예전 파일과 맞추려고 받기만 한다. */
    record ConfigFile(
            Long seed,
            LocalDate startDate,
            ZoneSetting defaultZone,
            List<ZoneSetting> zones,
            List<Double> hourWeights,
            Map<DayOfWeek, Double> dayOfWeekWeights,
            Map<Season, Double> seasonWeights,
            List<SurgeDay> surgeDays) {
    }
}
