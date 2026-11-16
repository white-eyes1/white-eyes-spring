package com.whiteeyes.admin.event.service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.whiteeyes.admin.camera.service.CameraQueryService;
import com.whiteeyes.admin.event.dto.EventListItem;
import com.whiteeyes.admin.event.dto.EventSearchRequest;
import com.whiteeyes.admin.region.dto.ZoneSummary;
import com.whiteeyes.admin.region.service.RegionLookupService;
import com.whiteeyes.admin.region.service.ZoneService;
import com.whiteeyes.common.event.entity.Event;
import com.whiteeyes.common.event.entity.EventType;
import com.whiteeyes.common.event.repository.EventRepository;
import com.whiteeyes.common.event.repository.EventSpecifications;
import com.whiteeyes.common.web.PageResponse;

/**
 * 이벤트 목록(FNC-A-036): 기간·지역·종류·처리 상태·검색어로 거른 이벤트를 발생 시각 내림차순으로 페이지 단위로 돌려준다.
 * 구역·캠·지역 이름은 이벤트마다 따로 읽지 않고 한 페이지 분을 모아 한 번씩만 읽는다.
 */
@Service
@RequiredArgsConstructor
public class EventListService {

    // DB는 KST를 오프셋 없이 저장한다
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    // 삭제된 구역 이름 뒤에 붙이는 표시
    private static final String DELETED_ZONE_SUFFIX = " (삭제됨)";
    /** 발생 시각 내림차순, 같으면 번호가 큰 것이 먼저. 엑셀(FNC-A-039)도 같은 순서로 쓴다. */
    public static final Sort LATEST_FIRST = Sort.by(Sort.Order.desc("startedAt"), Sort.Order.desc("id"));

    // 필드: 조회 조건 만들기, 이벤트 저장소, 구역·지역·캠 이름 조회
    private final EventConditionService eventConditionService;
    private final EventRepository eventRepository;
    private final ZoneService zoneService;
    private final RegionLookupService regionLookupService;
    private final CameraQueryService cameraQueryService;

    /**
     * 조건에 맞는 이벤트를 최신순 한 페이지로 돌려준다.
     */
    @Transactional(readOnly = true)
    public PageResponse<EventListItem> list(EventSearchRequest request) {
        // 기간·종류·구역 조건(처리 상태 제외). 맞는 구역이 없으면 비어 있다
        Optional<Specification<Event>> base = eventConditionService.toSpecification(request);
        // 맞는 구역이 없으니 DB를 조회하지 않고 빈 페이지를 돌려준다
        if (base.isEmpty()) {
            return PageResponse.of(List.of(), request.page(), request.size(), 0);
        }
        // 처리 상태 조건은 목록에서만 쓰므로 여기서 덧붙인다
        Specification<Event> condition =
                Specification.allOf(base.get(), EventSpecifications.ofStatuses(request.statuses()));
        // 최신순으로 한 페이지를 조회(총 건수도 함께 센다)
        Page<Event> events =
                eventRepository.findAll(condition, PageRequest.of(request.page(), request.size(), LATEST_FIRST));

        // 이름들을 한 번에 모아 읽어 화면용 행으로 바꾼다
        List<EventListItem> items = toItems(events.getContent());
        return PageResponse.of(items, request.page(), request.size(), events.getTotalElements());
    }

    /** 이벤트 1건을 목록의 한 행 모양으로 만든다. 상세(FNC-A-037)가 목록과 같은 필드를 재사용한다. */
    @Transactional(readOnly = true)
    // 한 건도 같은 변환 경로를 타게 해서 목록과 상세의 값이 어긋나지 않는다
    public EventListItem toListItem(Event event) {
        return toItems(List.of(event)).get(0);
    }

    /** 이벤트들을 목록 행으로 바꾼다(엑셀 FNC-A-039도 사용). 이름은 한 번에 모아 읽는다. 입력 순서가 그대로 유지된다. */
    public List<EventListItem> toItems(List<Event> events) {
        if (events.isEmpty()) {
            return List.of();
        }
        // 이번에 필요한 구역·캠 번호를 먼저 모아서 이름을 일괄 조회한다(이벤트마다 쿼리하는 N+1을 피한다)
        Set<Long> zoneIds = events.stream().map(Event::getZoneId).collect(Collectors.toSet());
        Set<Long> cameraIds =
                events.stream().map(Event::getCameraId).filter(Objects::nonNull).collect(Collectors.toSet());
        // 구역 번호 -> 구역 요약
        Map<Long, ZoneSummary> zones = zoneService.findSummaries(zoneIds);
        // 동 번호 -> '유성구 · 궁동' 같은 지역 경로
        Map<Long, String> regionPaths = regionLookupService.findPaths(
                zones.values().stream().map(ZoneSummary::dongId).collect(Collectors.toSet()));
        // 캠 이상 이벤트의 캠 이름. 캠이 없으면 조회 자체를 건너뛴다
        Map<Long, String> cameraNamesById =
                cameraIds.isEmpty() ? Map.of() : cameraQueryService.findNamesByIds(cameraIds);
        // 진행 중 이벤트의 지속 시간 계산 기준을 모든 행에서 같게 하려고 한 번만 구한다
        LocalDateTime now = LocalDateTime.now(KST);

        // stream 순서를 유지하므로 입력 순서가 그대로 결과 순서가 된다
        return events.stream()
                .map(event -> toItem(event, zones.get(event.getZoneId()), regionPaths, cameraNamesById, now))
                .toList();
    }

    /** 이벤트 한 건과 미리 읽어 둔 이름들로 목록 행을 만든다. */
    private EventListItem toItem(
            Event event,
            ZoneSummary zone,
            Map<Long, String> regionPaths,
            Map<Long, String> cameraNamesById,
            LocalDateTime now) {
        // 종료 시각이 없으면 진행 중
        boolean ongoing = event.getEndedAt() == null;
        // 지속 시간의 끝: 진행 중이면 지금, 끝났으면 종료 시각
        LocalDateTime until = ongoing ? now : event.getEndedAt();
        // zone이 null이면(구역 정보를 못 찾음) 이름·경로는 null
        return new EventListItem(
                event.getId(),
                event.getType(),
                event.getZoneId(),
                zone == null ? null : zoneNameOf(zone),
                zone == null ? null : regionPaths.get(zone.dongId()),
                cameraNameOf(event, cameraNamesById),
                zone != null && zone.deleted(),
                toKstOffset(event.getStartedAt()),
                ongoing ? null : toKstOffset(event.getEndedAt()),
                ongoing,
                // 시계 차이로 음수가 나오지 않게 0 이상으로 맞춘다
                Math.max(0, Duration.between(event.getStartedAt(), until).toSeconds()),
                event.getPeakLevel(),
                event.getPeakValue(),
                event.getPeakCount(),
                event.getStatus());
    }

    /** 삭제된 구역은 이름 뒤에 표시를 붙인다. */
    private static String zoneNameOf(ZoneSummary zone) {
        return zone.deleted() ? zone.name() + DELETED_ZONE_SUFFIX : zone.name();
    }

    // 캠 이상만 이벤트가 가리키는 캠 이름. 혼잡·급증은 구역 단위라 camera_id가 없으므로 null (PM 결정)
    // (위 주석 참고) CAM_FAULT만 캠 이름을 채운다
    private static String cameraNameOf(Event event, Map<Long, String> cameraNamesById) {
        if (event.getType() == EventType.CAM_FAULT && event.getCameraId() != null) {
            return cameraNamesById.get(event.getCameraId());
        }
        return null;
    }

    // DB에는 KST를 오프셋 없이 저장하므로 응답에서 +09:00을 붙인다
    private static OffsetDateTime toKstOffset(LocalDateTime time) {
        return time.atZone(KST).toOffsetDateTime();
    }
}
