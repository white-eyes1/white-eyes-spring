package com.whiteeyes.admin.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

// 예약 작업은 관리자 앱에서만 돌린다(두 앱에서 돌면 중복 저장, stagg-001).
// scheduling.enabled=false는 시험용이다. 전체 SQL 수를 세는 시험에 예약 작업 쿼리가 끼어들지 않게 끈다(spring #350)
@Configuration
@ConditionalOnExpression("${scheduling.enabled:true}")
@EnableScheduling
public class SchedulingConfig {}
