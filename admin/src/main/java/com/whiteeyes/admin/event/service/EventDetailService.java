package com.whiteeyes.admin.event.service;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import com.whiteeyes.admin.cleanup.config.RetentionProperties;
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

/**
 * 이벤트 상세(FNC-A-037): 이벤트 1건과 그 처리 이력(작업 기록의 EVENT_STATUS)을 돌려준다.
 * 처리 이력은 이벤트 전용 테이블이 아니라 work_history에서 찾는다(histwr-002).
 * 응답의 playbackAvailable은 상세 창의 발생 시점 영상·다시보기 이동(FNC-A-040)이 쓴다.
 */
@Service
@EnableConfigurationProperties(RetentionProperties.class)
@RequiredArgsConstructor
public class EventDetailService {

    // DB는 KST를 오프셋 없이 저장한다
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    // 필드: 이벤트 저장소, 목록 행 변환 재사용, 처리 이력(work_history) 조회, 녹화 보관 설정, JSON 파서
    private final EventRepository eventRepository;
    private final EventListService eventListService;
    private final WorkHistoryService workHistoryService;
    private final RetentionProperties retentionProperties;
    private final JsonMapper jsonMapper;

    /**
     * 이벤트 한 건의 상세를 만든다. 없는 번호면 EventNotFoundException(404).
     */
    @Transactional(readOnly = true)
    public EventDetailResponse find(Long eventId) {
        // 이벤트 조회. 없으면 404
        Event event = eventRepository.findById(eventId).orElseThrow(() -> new EventNotFoundException(eventId));
        // 이 이벤트의 처리 상태 변경 기록만 work_history에서 꺼내 화면용 항목으로 바꾼다(오래된 것부터)
        List<EventHistoryItem> histories =
                workHistoryService.findByTarget(WorkTargetType.EVENT, eventId, WorkAction.EVENT_STATUS).stream()
                        .map(this::toHistoryItem)
                        .toList();
        // 목록과 같은 변환(구역 이름·지역·지속 시간 등)을 재사용해 두 화면이 어긋나지 않게 하고, 상세 전용 값을 더한다
        return EventDetailResponse.of(
                eventListService.toListItem(event),
                // 저장된 기준값 JSON 문자열을 JSON 객체로 풀어 응답에 그대로 싣는다
                jsonMapper.readTree(event.getThresholdSnapshot()),
                event.getCameraId(),
                event.getActionCode(),
                event.getActionNote(),
                event.getHandledBy(),
                // 처리 전이면 null, 아니면 +09:00 오프셋을 붙인다
                event.getHandledAt() == null ? null : toKstOffset(event.getHandledAt()),
                histories,
                isPlaybackAvailable(event));
    }

    // 혼잡·급증만, 발생이 녹화 보관 기간(RECORDING_RETENTION_DAYS) 안일 때만(FNC-A-040, screens.md SCR-A-011, evt-004).
    // 다시보기 녹화 찾기(FNC-A-043)와 같은 설정을 쓴다. 녹화 파일이 있는지는 다시보기가 안내한다
    /** 위 설명대로 다시보기가 가능한 이벤트인지 계산한다. */
    private boolean isPlaybackAvailable(Event event) {
        // 캠 이상은 구역 영상이 아니라 제외. 나머지는 발생이 (지금 - 녹화 보관 일수)보다 이전이 아니어야 한다
        return event.getType() != EventType.CAM_FAULT
                && !event.getStartedAt()
                        .isBefore(LocalDateTime.now(KST).minusDays(retentionProperties.recordingDays()));
    }

    /** 작업 기록 한 건을 처리 이력 한 줄로 바꾼다. 이전·이후 상태와 조치는 기록의 detail JSON에서 꺼낸다. */
    private EventHistoryItem toHistoryItem(WorkHistory history) {
        // detail이 없는 기록도 있을 수 있어 null을 허용한다
        JsonNode detail = history.getDetail() == null ? null : jsonMapper.readTree(history.getDetail());
        return new EventHistoryItem(
                toKstOffset(history.getCreatedAt()),
                history.getActorLoginId(),
                statusOf(detail, "from"),
                statusOf(detail, "to"),
                textOf(detail, "actionCode"),
                textOf(detail, "actionNote"));
    }

    /** detail JSON의 필드를 처리 상태로 읽는다. 없거나 알 수 없는 값이면 null. */
    private static EventStatus statusOf(JsonNode detail, String field) {
        String text = textOf(detail, field);
        if (text == null) {
            return null;
        }
        // work_history.detail에 모르는 값이 있어도 상세 전체가 실패하지 않게 null로 둔다
        try {
            return EventStatus.valueOf(text);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** detail JSON에서 문자열 필드를 읽는다. 필드가 없거나 JSON null이면 null. */
    private static String textOf(JsonNode detail, String field) {
        if (detail == null || !detail.hasNonNull(field)) {
            return null;
        }
        return detail.get(field).asString();
    }

    // DB에는 KST를 오프셋 없이 저장하므로 응답에서 +09:00을 붙인다
    // (위 설명 참고) KST 시각에 오프셋을 붙여 클라이언트가 시간대를 알 수 있게 한다
    private static OffsetDateTime toKstOffset(LocalDateTime time) {
        return time.atZone(KST).toOffsetDateTime();
    }
}
