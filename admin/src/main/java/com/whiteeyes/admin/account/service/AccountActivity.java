package com.whiteeyes.admin.account.service;

import java.time.LocalDateTime;

/**
 * 한 계정의 최근 활동(FNC-A-002, aclst-004).
 *
 * @param lastActiveAt 최근 활동 시각(KST). 세션도 로그인 기록도 없으면 null
 * @param active 세션이 있고 그 마지막 접근 시각이 활동 중 판단 기준 시간 안이면 true(세션이 없으면 false)
 */
public record AccountActivity(LocalDateTime lastActiveAt, boolean active) {}
