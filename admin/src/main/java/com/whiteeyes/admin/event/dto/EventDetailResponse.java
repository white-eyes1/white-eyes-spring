package com.whiteeyes.admin.event.dto;

import com.whiteeyes.common.event.entity.EventStatus;
import com.whiteeyes.common.event.entity.EventType;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * 이벤트 상세(api/public.md GET /api/v1/events/{id}, FNC-A-037). 목록 행({@link EventListItem})의 필드에
 * 당시 기준값·처리 정보·처리 이력·다시보기 가능 여부가 더해진다.
 *
 * @param thresholdSnapshot 발생 당시 기준값(JSON 객체)
 * @param cameraId 캠 이상일 때만 값이 있다
 * @param handledBy 마지막으로 처리 상태를 바꾼 관리자 번호(admin_account.id)
 * @param histories 처리 이력, 오래된 것부터
 * @param playbackAvailable 혼잡·급증이고 발생이 녹화 보관 기간(30일) 안이면 true
 */
public record EventDetailResponse(
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
        EventStatus status,
        JsonNode thresholdSnapshot,
        Long cameraId,
        String actionCode,
        String actionNote,
        Long handledBy,
        OffsetDateTime handledAt,
        List<EventHistoryItem> histories,
        boolean playbackAvailable) {

    public static EventDetailResponse of(
            EventListItem item,
            JsonNode thresholdSnapshot,
            Long cameraId,
            String actionCode,
            String actionNote,
            Long handledBy,
            OffsetDateTime handledAt,
            List<EventHistoryItem> histories,
            boolean playbackAvailable) {
        return new EventDetailResponse(
                item.id(),
                item.type(),
                item.zoneId(),
                item.zoneName(),
                item.regionPath(),
                item.cameraName(),
                item.zoneDeleted(),
                item.startedAt(),
                item.endedAt(),
                item.ongoing(),
                item.durationSec(),
                item.peakLevel(),
                item.peakValue(),
                item.peakCount(),
                item.status(),
                thresholdSnapshot,
                cameraId,
                actionCode,
                actionNote,
                handledBy,
                handledAt,
                histories,
                playbackAvailable);
    }
}
