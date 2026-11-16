package com.whiteeyes.tools.config;

import java.time.Clock;
import java.time.ZoneId;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 현재 시각을 테스트에서 고정할 수 있게 Clock을 빈으로 둔다. DB 시각은 KST로 저장한다(conventions.md '시각 형식'). */
@Configuration
public class ClockConfig {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    /**
     * 한국 시간(KST) 기준 시계. 서비스가 LocalDateTime.now(clock)처럼 이 시계로 현재 시각을 읽는다.
     */
    @Bean
    public Clock clock() {
        return Clock.system(KST);
    }
}
