/*
 * [해석본] 원본: admin/src/main/java/com/whiteeyes/admin/config/SchedulingConfig.java
 * 관련 이슈: #18(FNC-A-028)
 *
 * [무엇] @Scheduled가 붙은 메서드가 실제로 돌도록 스프링의 예약 작업 기능을 켠다.
 *   이 설정이 없으면 StatAggregationScheduler의 @Scheduled는 아무 일도 하지 않는다.
 * [왜 admin에만] 1분 통계를 두 앱(user·admin)에서 모두 저장하면 같은 행을 두 번 쓴다. 그래서 집계는 관리자 앱에서만
 *   돈다(requirements.md stagg-001, package-structure.md). user 모듈에는 이 설정을 두지 않는다.
 * [왜 config 패키지] package-structure.md가 admin의 config 패키지 설명에 "스케줄러"를 적어 두었다.
 *   AdminApplication에 직접 붙이지 않은 것도 같은 이유다(설정은 config에 모음).
 */
package com.whiteeyes.admin.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

// 예약 작업은 관리자 앱에서만 돌린다(두 앱에서 돌면 중복 저장, stagg-001)
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
