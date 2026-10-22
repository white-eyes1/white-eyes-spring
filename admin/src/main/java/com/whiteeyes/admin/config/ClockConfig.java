package com.whiteeyes.admin.config;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 현재 시각을 테스트에서 고정할 수 있게 Clock을 빈으로 둔다. DB 시각은 KST로 저장한다(conventions.md '시각 형식'). */
@Configuration
public class ClockConfig {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Bean
    public Clock clock() {
        return Clock.system(KST);
    }
}
