package com.whiteeyes.admin.event.service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.whiteeyes.admin.event.dto.EventDetailResponse;
import com.whiteeyes.admin.event.dto.EventStatusUpdateRequest;
import com.whiteeyes.admin.event.exception.EventNotFoundException;
import com.whiteeyes.admin.event.exception.EventStatusConflictException;
import com.whiteeyes.admin.history.service.AdminWorkRecorder;
import com.whiteeyes.admin.notification.service.NotificationPublisher;
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

/**
 * 이벤트 처리 상태 변경(FNC-A-038): 미확인 → 확인 중 → 조치완료. 상태 저장과 작업 기록은 한 트랜잭션이다.
 * 이벤트 행을 잠그고 읽어서 두 관리자가 동시에 처리하면 한 명만 성공한다.
 * 처리 순서 6: 알림 SSE로 미확인 수를 갱신한다(FNC-A-048). 커밋된 뒤에 보낸다.
 */
@Service
@RequiredArgsConstructor
public class EventStatusService {

    // 처리 시각을 DB 기준(KST, 오프셋 없음)으로 기록한다
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    // 필드: 이벤트 저장소(행 잠금 조회 포함), 구역 이름, 작업 기록, 변경 후 상세 재조회, 미확인 수 알림
    private final EventRepository eventRepository;
    private final ZoneService zoneService;
    private final AdminWorkRecorder workRecorder;
    private final EventDetailService eventDetailService;
    private final NotificationPublisher notificationPublisher;

    /**
     * 처리 상태를 바꾸고 바뀐 상세를 돌려준다. 순서와 번호는 명세(FNC-A-038 처리 순서)를 따른다.
     * 하나의 트랜잭션이라 중간에 예외가 나면 상태 변경과 작업 기록이 함께 되돌려진다.
     */
    @Transactional
    public EventDetailResponse changeStatus(Long eventId, EventStatusUpdateRequest request) {
        // 로그인하지 않은 요청은 상태를 바꾸기 전에 거부한다(작업 기록의 행동한 계정을 채울 수 없다)
        LoginAccount actor = workRecorder.requireActor();
        // 처리 순서 3: 조치완료면 조치 항목 필수, OTHER면 메모 필수
        String actionNote = validateAction(request);

        // 'ForUpdate'는 이벤트 행에 잠금을 거는 조회다. 두 관리자가 동시에 같은 이벤트를 바꾸려 해도 한 명이 끝날 때까지 다른 한 명이 기다리고, 그 뒤 아래 상태 비교에서 충돌(409)로
        // 걸러진다
        Event event = eventRepository.findByIdForUpdate(eventId).orElseThrow(() -> new EventNotFoundException(eventId));

        // 처리 순서 1: 화면이 보던 상태와 다르면 다른 관리자가 먼저 처리한 것이다
        // 변경 전 상태(작업 기록에도 남긴다)
        EventStatus from = event.getStatus();
        if (from != request.expectedStatus()) {
            throw EventStatusConflictException.changedByOther();
        }
        // 처리 순서 2: 앞으로만 변경 가능
        // 상태는 미확인 -> 확인 중 -> 조치완료로 앞으로만 간다. 같은 상태이거나 뒤로 가는 요청은 거부한다
        if (!from.canChangeTo(request.status())) {
            throw EventStatusConflictException.notForward();
        }

        // 처리 순서 4: 상태·조치 항목·처리자·처리 시각 저장
        // 처리 시각
        LocalDateTime now = LocalDateTime.now(KST);
        // 조치완료는 조치 항목·메모·처리자·시각을 함께 저장하고, 확인 중은 처리자·시각만 저장한다
        if (request.status() == EventStatus.RESOLVED) {
            event.resolve(request.actionCode(), actionNote, actor.accountId(), now);
        } else {
            event.markChecked(actor.accountId(), now);
        }
        // 처리 순서 5: 작업 기록. 상세의 처리 이력이 이 detail을 읽는다(FNC-A-037)
        // 상세 화면의 처리 이력이 이 기록의 detail(from·to·조치)을 읽는다
        workRecorder.record(
                actor,
                WorkAction.EVENT_STATUS,
                WorkTargetType.EVENT,
                eventId,
                targetNameOf(event),
                detailOf(from, request, actionNote));
        // 처리 순서 6: 알림 SSE로 미확인 수 갱신
        // 미확인 수가 줄었을 수 있으므로 열려 있는 화면에 알린다(커밋 이후 발송은 발행기가 맡는다)
        notificationPublisher.uncheckedCountChanged();
        // 같은 트랜잭션 안에서 바뀐 값을 다시 읽어 상세 응답으로 돌려준다
        return eventDetailService.find(eventId);
    }

    // 조치 항목·메모는 조치완료일 때만 값이 있다(tables.md event.action_code). 저장할 메모(비었으면 null)를 돌려준다
    /**
     * 조치 항목·메모 입력 규칙을 검사한다. 위반하면 400 INVALID_INPUT.
     */
    private static String validateAction(EventStatusUpdateRequest request) {
        // 조치완료가 아닌 변경에 조치 값이 오면 잘못된 요청
        if (request.status() != EventStatus.RESOLVED) {
            if (request.actionCode() != null || request.actionNote() != null) {
                throw new BusinessException(ErrorCode.INVALID_INPUT, "조치 항목과 메모는 조치완료일 때만 보낼 수 있습니다.");
            }
            return null;
        }
        // 조치완료로 바꾸려면 조치 항목이 있어야 한다
        if (request.actionCode() == null) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "조치 항목을 선택해 주세요.");
        }
        // 공백뿐인 메모는 없는 것으로 본다
        String note = request.actionNote() == null || request.actionNote().isBlank() ? null : request.actionNote();
        // '기타'는 이유를 알 수 없으므로 메모가 필수
        if (request.actionCode() == EventActionCode.OTHER && note == null) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "조치 항목이 기타이면 메모를 입력해 주세요.");
        }
        return note;
    }

    // 기록 당시 대상 이름: "궁동 1구역 혼잡"(api/public.md 작업 기록 예시)
    // (위 주석 참고) 구역을 못 찾으면 번호로 대신한다
    private String targetNameOf(Event event) {
        ZoneSummary zone = zoneService.findSummaries(Set.of(event.getZoneId())).get(event.getZoneId());
        String zoneName = zone == null ? "구역 " + event.getZoneId() : zone.name();
        return zoneName + " " + typeLabelOf(event.getType());
    }

    /** 이벤트 종류의 화면 이름. switch 식이라 종류가 늘면 컴파일 단계에서 누락을 알 수 있다. */
    private static String typeLabelOf(EventType type) {
        return switch (type) {
            case CONGESTION -> "혼잡";
            case SURGE -> "급증";
            case CAM_FAULT -> "캠 이상";
        };
    }

    /** 작업 기록 detail. 상세 화면 처리 이력이 읽는 키(from, to, actionCode, actionNote)이므로 이름을 바꾸면 안 된다. */
    private static Map<String, Object> detailOf(EventStatus from, EventStatusUpdateRequest request, String actionNote) {
        // 넣은 순서대로 JSON이 나가도록 LinkedHashMap
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("from", from);
        detail.put("to", request.status());
        detail.put("actionCode", request.actionCode());
        detail.put("actionNote", actionNote);
        return detail;
    }
}
