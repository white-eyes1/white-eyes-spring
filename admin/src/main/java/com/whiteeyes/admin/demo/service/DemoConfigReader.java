package com.whiteeyes.admin.demo.service;

import java.nio.file.Path;

import org.springframework.stereotype.Component;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** 시연용 통계 생성 설정 파일(JSON)을 읽고 검사한다(FNC-A-051 입력 항목 --config). */
@Component
public class DemoConfigReader {

    // 철자가 틀린 항목이 조용히 기본값으로 바뀌지 않게 모르는 항목이 있으면 멈춘다
    private final JsonMapper jsonMapper = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    /**
     * @throws IllegalArgumentException 파일을 읽을 수 없거나 설정이 틀렸을 때
     */
    public DemoGenerateConfig read(Path file) {
        DemoGenerateConfig config;
        try {
            config = jsonMapper.readValue(file.toFile(), DemoGenerateConfig.class);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("시연용 통계 설정 파일을 읽을 수 없습니다: " + file, e);
        }
        config.validate();
        return config;
    }
}
