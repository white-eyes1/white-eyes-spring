package com.whiteeyes.admin.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

// 예약 작업은 관리자 앱에서만 돌린다(두 앱에서 돌면 중복 저장, stagg-001)
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
