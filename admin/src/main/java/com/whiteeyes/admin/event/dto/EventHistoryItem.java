package com.whiteeyes.admin.event.dto;

import com.whiteeyes.common.event.entity.EventStatus;
import java.time.OffsetDateTime;

/**
 * 이벤트 처리 이력의 한 줄(api/public.md GET /api/v1/events/{id} histories, FNC-A-037).
 *
 * @param loginId 처리한 관리자의 기록 당시 아이디
 * @param from 이전 처리 상태. 기록에 없으면 null
 * @param to 바뀐 처리 상태. 기록에 없으면 null
 * @param actionCode 조치완료로 바꿀 때 고른 조치 항목. 아니면 null
 * @param actionNote 조치 메모. 없으면 null
 */
public record EventHistoryItem(
        OffsetDateTime at,
        String loginId,
        EventStatus from,
        EventStatus to,
        String actionCode,
        String actionNote) {}
