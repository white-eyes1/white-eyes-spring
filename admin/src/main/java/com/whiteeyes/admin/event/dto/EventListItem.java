package com.whiteeyes.admin.event.dto;

import com.whiteeyes.common.event.entity.EventStatus;
import com.whiteeyes.common.event.entity.EventType;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 이벤트 목록의 한 행(api/public.md GET /api/v1/events, FNC-A-036).
 *
 * @param zoneName 삭제된 구역이면 뒤에 " (삭제됨)"이 붙는다
 * @param regionPath "유성구 · 궁동"
 * @param cameraName 캠 이상이면 이벤트의 캠 이름, 혼잡·급증은 구역 단위라 null
 * @param durationSec 진행 중이면 발생 시각부터 지금까지
 * @param peakLevel 혼잡 이벤트만
 * @param peakValue 혼잡은 밀집도(명/㎡), 급증은 증가율(%), 캠 이상은 null
 * @param peakCount 캠 이상은 null
 */
public record EventListItem(
        Long id,
        EventType type,
        Long zoneId,
        String zoneName,
        String regionPath,
        String cameraName,
        boolean zoneDeleted,
        OffsetDateTime startedAt,
        OffsetDateTime endedAt,
        boolean ongoing,
        long durationSec,
        Integer peakLevel,
        BigDecimal peakValue,
        Integer peakCount,
        EventStatus status) {}
