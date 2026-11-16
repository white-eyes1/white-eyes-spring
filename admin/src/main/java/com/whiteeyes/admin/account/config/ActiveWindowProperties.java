package com.whiteeyes.admin.account.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 활동 중 판단 기준 시간(conventions.md '활동 중 판단'). application.yml이 환경변수 ACTIVE_WINDOW를 연결한다.
 *
 * 설정 접두사가 account라 실제 설정 키는 account.active-window다. 값이 없으면 @DefaultValue의 30분을 쓴다.
 * AccountActivityService가 '지금 - 이 시간'을 기준 시각으로 삼아 목록의 활동 중 여부를 판정한다.
 *
 * @param activeWindow 최근 활동 시각이 이 시간 안이면 활동 중
 */
@ConfigurationProperties(prefix = "account")
public record ActiveWindowProperties(@DefaultValue("30m") Duration activeWindow) {}
