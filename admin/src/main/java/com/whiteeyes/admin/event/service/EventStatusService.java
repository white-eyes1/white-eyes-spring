package com.whiteeyes.admin.event.service;

import com.whiteeyes.admin.event.dto.EventDetailResponse;
import com.whiteeyes.admin.event.dto.EventStatusUpdateRequest;
import com.whiteeyes.admin.event.exception.EventNotFoundException;
import com.whiteeyes.admin.event.exception.EventStatusConflictException;
import com.whiteeyes.admin.history.service.AdminWorkRecorder;
import com.whiteeyes.admin.region.dto.ZoneSummary;
import com.whiteeyes.admin.region.service.ZoneService;
import com.whiteeyes.common.event.entity.Event;
import com.whiteeyes.common.event.entity.EventActionCode;
import com.whiteeyes.common.event.entity.EventStatus;
import com.whiteeyes.common.event.entity.EventType;
import com.whiteeyes.common.event.repository.EventRepository;
import com.whiteeyes.common.exception.BusinessException;
import com.whiteeyes.common.exception.ErrorCode;
import com.whiteeyes.common.history.entity.WorkAction;
import com.whiteeyes.common.history.entity.WorkTargetType;
import com.whiteeyes.common.security.LoginAccount;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 이벤트 처리 상태 변경(FNC-A-038): 미확인 → 확인 중 → 조치완료. 상태 저장과 작업 기록은 한 트랜잭션이다.
 * 이벤트 행을 잠그고 읽어서 두 관리자가 동시에 처리하면 한 명만 성공한다.
 * 처리 순서 6(알림 SSE로 미확인 수 갱신)은 알림 스트림(FNC-A-048, #46)이 맡는다.
 */
@Service
@RequiredArgsConstructor
public class EventStatusService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final EventRepository eventRepository;
    private final ZoneService zoneService;
    private final AdminWorkRecorder workRecorder;
    private final EventDetailService eventDetailService;

    @Transactional
    public EventDetailResponse changeStatus(Long eventId, EventStatusUpdateRequest request) {
        // 로그인하지 않은 요청은 상태를 바꾸기 전에 거부한다(작업 기록의 행동한 계정을 채울 수 없다)
        LoginAccount actor = workRecorder.requireActor();
        // 처리 순서 3: 조치완료면 조치 항목 필수, OTHER면 메모 필수
        String actionNote = validateAction(request);

        Event event = eventRepository.findByIdForUpdate(eventId).orElseThrow(() -> new EventNotFoundException(eventId));

        // 처리 순서 1: 화면이 보던 상태와 다르면 다른 관리자가 먼저 처리한 것이다
        EventStatus from = event.getStatus();
        if (from != request.expectedStatus()) {
            throw EventStatusConflictException.changedByOther();
        }
        // 처리 순서 2: 앞으로만 변경 가능
        if (!from.canChangeTo(request.status())) {
            throw EventStatusConflictException.notForward();
        }

        // 처리 순서 4: 상태·조치 항목·처리자·처리 시각 저장
        LocalDateTime now = LocalDateTime.now(KST);
        if (request.status() == EventStatus.RESOLVED) {
            event.resolve(request.actionCode(), actionNote, actor.accountId(), now);
        } else {
            event.markChecked(actor.accountId(), now);
        }
        // 처리 순서 5: 작업 기록. 상세의 처리 이력이 이 detail을 읽는다(FNC-A-037)
        workRecorder.record(
                actor,
                WorkAction.EVENT_STATUS,
                WorkTargetType.EVENT,
                eventId,
                targetNameOf(event),
                detailOf(from, request, actionNote));
        return eventDetailService.find(eventId);
    }

    // 조치 항목·메모는 조치완료일 때만 값이 있다(tables.md event.action_code). 저장할 메모(비었으면 null)를 돌려준다
    private static String validateAction(EventStatusUpdateRequest request) {
        if (request.status() != EventStatus.RESOLVED) {
            if (request.actionCode() != null || request.actionNote() != null) {
                throw new BusinessException(ErrorCode.INVALID_INPUT, "조치 항목과 메모는 조치완료일 때만 보낼 수 있습니다.");
            }
            return null;
        }
        if (request.actionCode() == null) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "조치 항목을 선택해 주세요.");
        }
        String note = request.actionNote() == null || request.actionNote().isBlank() ? null : request.actionNote();
        if (request.actionCode() == EventActionCode.OTHER && note == null) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "조치 항목이 기타이면 메모를 입력해 주세요.");
        }
        return note;
    }

    // 기록 당시 대상 이름: "궁동 1구역 혼잡"(api/public.md 작업 기록 예시)
    private String targetNameOf(Event event) {
        ZoneSummary zone = zoneService.findSummaries(Set.of(event.getZoneId())).get(event.getZoneId());
        String zoneName = zone == null ? "구역 " + event.getZoneId() : zone.name();
        return zoneName + " " + typeLabelOf(event.getType());
    }

    private static String typeLabelOf(EventType type) {
        return switch (type) {
            case CONGESTION -> "혼잡";
            case SURGE -> "급증";
            case CAM_FAULT -> "캠 이상";
        };
    }

    private static Map<String, Object> detailOf(EventStatus from, EventStatusUpdateRequest request, String actionNote) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("from", from);
        detail.put("to", request.status());
        detail.put("actionCode", request.actionCode());
        detail.put("actionNote", actionNote);
        return detail;
    }
}
