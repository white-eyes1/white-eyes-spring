package com.whiteeyes.admin.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

// 예약 작업은 관리자 앱에서만 돌린다(두 앱에서 돌면 중복 저장, stagg-001).
// 1회 실행 작업(--job=…)으로 뜬 인스턴스에서는 켜지 않는다. 떠 있는 웹 인스턴스와 같은 집계·정리·캠 이상 감시가
// 겹쳐 돌고, 결과를 받지 못하는 작업 인스턴스가 가동 중인 캠을 캠 이상으로 잘못 판정하기 때문이다(spring #280)
@Configuration
@ConditionalOnExpression("'${job:}'.isEmpty()")
@EnableScheduling
public class SchedulingConfig {
}
