package com.whiteeyes.admin.notification.dto;

import com.whiteeyes.common.event.entity.EventType;
import java.time.OffsetDateTime;

/**
 * 알림 SSE의 event-created 데이터(FNC-A-048). level은 혼잡 이벤트의 최고 단계(2 주의 / 3 위험)이고
 * 급증·캠 이상은 null이다.
 */
public record EventCreatedMessage(Long id, EventType type, String zoneName, Integer level, OffsetDateTime startedAt) {}
