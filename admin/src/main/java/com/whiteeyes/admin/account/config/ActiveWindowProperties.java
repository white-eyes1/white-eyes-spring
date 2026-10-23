package com.whiteeyes.admin.account.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 활동 중 판단 기준 시간(conventions.md '활동 중 판단'). application.yml이 환경변수 ACTIVE_WINDOW를 연결한다.
 *
 * @param activeWindow 최근 활동 시각이 이 시간 안이면 활동 중
 */
@ConfigurationProperties(prefix = "account")
public record ActiveWindowProperties(@DefaultValue("30m") Duration activeWindow) {}
