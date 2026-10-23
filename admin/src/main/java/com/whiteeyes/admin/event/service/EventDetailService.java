package com.whiteeyes.admin.event.service;

import com.whiteeyes.admin.event.dto.EventDetailResponse;
import com.whiteeyes.admin.event.dto.EventHistoryItem;
import com.whiteeyes.admin.event.exception.EventNotFoundException;
import com.whiteeyes.common.event.entity.Event;
import com.whiteeyes.common.event.entity.EventStatus;
import com.whiteeyes.common.event.entity.EventType;
import com.whiteeyes.common.event.repository.EventRepository;
import com.whiteeyes.common.history.entity.WorkAction;
import com.whiteeyes.common.history.entity.WorkHistory;
import com.whiteeyes.common.history.entity.WorkTargetType;
import com.whiteeyes.common.history.service.WorkHistoryService;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 이벤트 상세(FNC-A-037): 이벤트 1건과 그 처리 이력(작업 기록의 EVENT_STATUS)을 돌려준다.
 * 처리 이력은 이벤트 전용 테이블이 아니라 work_history에서 찾는다(histwr-002).
 */
@Service
@RequiredArgsConstructor
public class EventDetailService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    // 녹화 보관 기간(RECORDING_RETENTION_DAYS 기본 30일). 다시보기 이동은 이 안의 이벤트만 된다(evt-004)
    private static final int PLAYBACK_RETENTION_DAYS = 30;

    private final EventRepository eventRepository;
    private final EventListService eventListService;
    private final WorkHistoryService workHistoryService;
    private final JsonMapper jsonMapper;

    @Transactional(readOnly = true)
    public EventDetailResponse find(Long eventId) {
        Event event = eventRepository.findById(eventId).orElseThrow(() -> new EventNotFoundException(eventId));
        List<EventHistoryItem> histories =
                workHistoryService.findByTarget(WorkTargetType.EVENT, eventId, WorkAction.EVENT_STATUS).stream()
                        .map(this::toHistoryItem)
                        .toList();
        return EventDetailResponse.of(
                eventListService.toListItem(event),
                jsonMapper.readTree(event.getThresholdSnapshot()),
                event.getCameraId(),
                event.getActionCode(),
                event.getActionNote(),
                event.getHandledBy(),
                event.getHandledAt() == null ? null : toKstOffset(event.getHandledAt()),
                histories,
                isPlaybackAvailable(event));
    }

    // 혼잡·급증만, 발생이 보관 기간 안일 때만(screens.md SCR-A-011). 녹화 파일이 있는지는 다시보기(FNC-A-040·043)가 안내한다
    private static boolean isPlaybackAvailable(Event event) {
        return event.getType() != EventType.CAM_FAULT
                && !event.getStartedAt().isBefore(LocalDateTime.now(KST).minusDays(PLAYBACK_RETENTION_DAYS));
    }

    private EventHistoryItem toHistoryItem(WorkHistory history) {
        JsonNode detail = history.getDetail() == null ? null : jsonMapper.readTree(history.getDetail());
        return new EventHistoryItem(
                toKstOffset(history.getCreatedAt()),
                history.getActorLoginId(),
                statusOf(detail, "from"),
                statusOf(detail, "to"),
                textOf(detail, "actionCode"),
                textOf(detail, "actionNote"));
    }

    private static EventStatus statusOf(JsonNode detail, String field) {
        String text = textOf(detail, field);
        return text == null ? null : EventStatus.valueOf(text);
    }

    private static String textOf(JsonNode detail, String field) {
        if (detail == null || !detail.hasNonNull(field)) {
            return null;
        }
        return detail.get(field).asString();
    }

    // DB에는 KST를 오프셋 없이 저장하므로 응답에서 +09:00을 붙인다
    private static OffsetDateTime toKstOffset(LocalDateTime time) {
        return time.atZone(KST).toOffsetDateTime();
    }
}
