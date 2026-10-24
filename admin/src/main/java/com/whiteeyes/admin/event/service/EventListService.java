package com.whiteeyes.admin.event.service;

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

/**
 * 이벤트 목록(FNC-A-036): 기간·지역·종류·처리 상태·검색어로 거른 이벤트를 발생 시각 내림차순으로 페이지 단위로 돌려준다.
 * 구역·캠·지역 이름은 이벤트마다 따로 읽지 않고 한 페이지 분을 모아 한 번씩만 읽는다.
 */
@Service
@RequiredArgsConstructor
public class EventListService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final String DELETED_ZONE_PREFIX = "(삭제됨) ";
    private static final Sort LATEST_FIRST = Sort.by(Sort.Order.desc("startedAt"), Sort.Order.desc("id"));

    private final EventConditionService eventConditionService;
    private final EventRepository eventRepository;
    private final ZoneService zoneService;
    private final RegionLookupService regionLookupService;
    private final CameraQueryService cameraQueryService;

    @Transactional(readOnly = true)
    public PageResponse<EventListItem> list(EventSearchRequest request) {
        Optional<Specification<Event>> base = eventConditionService.toSpecification(request);
        if (base.isEmpty()) {
            return PageResponse.of(List.of(), request.page(), request.size(), 0);
        }
        Specification<Event> condition = Specification.allOf(base.get(), EventSpecifications.ofStatuses(request.statuses()));
        Page<Event> events =
                eventRepository.findAll(condition, PageRequest.of(request.page(), request.size(), LATEST_FIRST));

        List<EventListItem> items = toItems(events.getContent());
        return PageResponse.of(items, request.page(), request.size(), events.getTotalElements());
    }

    private List<EventListItem> toItems(List<Event> events) {
        if (events.isEmpty()) {
            return List.of();
        }
        Set<Long> zoneIds = events.stream().map(Event::getZoneId).collect(Collectors.toSet());
        Set<Long> cameraIds = events.stream()
                .map(Event::getCameraId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, ZoneSummary> zones = zoneService.findSummaries(zoneIds);
        Map<Long, String> regionPaths = regionLookupService.findPaths(
                zones.values().stream().map(ZoneSummary::dongId).collect(Collectors.toSet()));
        Map<Long, String> cameraNamesById = cameraIds.isEmpty() ? Map.of() : cameraQueryService.findNamesByIds(cameraIds);
        LocalDateTime now = LocalDateTime.now(KST);

        return events.stream()
                .map(event -> toItem(event, zones.get(event.getZoneId()), regionPaths, cameraNamesById, now))
                .toList();
    }

    private EventListItem toItem(
            Event event,
            ZoneSummary zone,
            Map<Long, String> regionPaths,
            Map<Long, String> cameraNamesById,
            LocalDateTime now) {
        boolean ongoing = event.getEndedAt() == null;
        LocalDateTime until = ongoing ? now : event.getEndedAt();
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
                Math.max(0, Duration.between(event.getStartedAt(), until).toSeconds()),
                event.getPeakLevel(),
                event.getPeakValue(),
                event.getPeakCount(),
                event.getStatus());
    }

    private static String zoneNameOf(ZoneSummary zone) {
        return zone.deleted() ? DELETED_ZONE_PREFIX + zone.name() : zone.name();
    }

    // 캠 이상만 이벤트가 가리키는 캠 이름. 혼잡·급증은 구역 단위라 camera_id가 없으므로 null (PM 결정)
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
