package com.whiteeyes.admin.event.service;

import com.whiteeyes.admin.camera.service.CameraQueryService;
import com.whiteeyes.admin.event.dto.EventSearchRequest;
import com.whiteeyes.admin.region.service.RegionLookupService;
import com.whiteeyes.admin.region.service.ZoneService;
import com.whiteeyes.common.event.entity.Event;
import com.whiteeyes.common.event.repository.EventSpecifications;
import com.whiteeyes.common.exception.BusinessException;
import com.whiteeyes.common.exception.ErrorCode;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 이벤트 목록·요약(FNC-A-036)이 함께 쓰는 조회 조건을 만든다. 처리 상태 조건은 넣지 않는다(요약은 상태를 무시하므로
 * 목록이 따로 붙인다).
 *
 * <p>지역·구역·검색어는 "맞는 구역 번호 집합"으로 바꿔 구역 번호 조건 하나로 만든다. 구역·캠·지역 이름은 각 도메인의
 * service를 거쳐 찾는다(package-structure.md 3-2).
 */
@Service
@RequiredArgsConstructor
public class EventConditionService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final ZoneService zoneService;
    private final RegionLookupService regionLookupService;
    private final CameraQueryService cameraQueryService;

    /**
     * 입력을 검사하고 조회 조건으로 바꾼다.
     *
     * @return 맞는 이벤트가 있을 수 없으면(지역에 구역이 없거나 검색어에 맞는 구역이 없음) 빈 값
     * @throws BusinessException 지역과 구역을 함께 줬거나 기간이 거꾸로면(400 INVALID_INPUT), 없는 지역이면(404)
     */
    @Transactional(readOnly = true)
    public Optional<Specification<Event>> toSpecification(EventSearchRequest request) {
        validate(request);

        Set<Long> zoneIds = resolveZoneIds(request);
        if (zoneIds != null && zoneIds.isEmpty()) {
            return Optional.empty();
        }

        List<Specification<Event>> conditions = new ArrayList<>();
        conditions.add(EventSpecifications.startedBetween(toKst(request.from()), toKst(request.to())));
        conditions.add(EventSpecifications.ofTypes(request.types()));
        if (zoneIds != null) {
            conditions.add(EventSpecifications.inZones(zoneIds));
        }
        return Optional.of(Specification.allOf(conditions));
    }

    private void validate(EventSearchRequest request) {
        if (request.regionId() != null && request.zoneId() != null) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "지역과 구역은 함께 지정할 수 없습니다.");
        }
        if (request.from() != null && request.to() != null && !request.from().isBefore(request.to())) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "시작 시각은 끝 시각보다 앞이어야 합니다.");
        }
    }

    /** 지역·구역·검색어가 모두 비어 있으면 null(구역으로는 거르지 않음), 아니면 맞는 구역 번호들. */
    private Set<Long> resolveZoneIds(EventSearchRequest request) {
        Set<Long> scoped = null;
        if (request.zoneId() != null) {
            scoped = Set.of(request.zoneId());
        } else if (request.regionId() != null) {
            List<Long> dongIds = regionLookupService.requireDongIdsUnder(request.regionId());
            scoped = dongIds.isEmpty() ? Set.of() : new HashSet<>(zoneService.findAllZoneIdsInDongs(dongIds));
        }
        if (request.keyword() == null) {
            return scoped;
        }
        Set<Long> matched = findZoneIdsByKeyword(request.keyword());
        if (scoped != null) {
            matched.retainAll(scoped);
        }
        return matched;
    }

    /** 구역 이름, 캠 이름, 소속 구·동 이름 중 하나라도 검색어가 들어 있는 구역(삭제된 구역·캠 포함). */
    private Set<Long> findZoneIdsByKeyword(String keyword) {
        Set<Long> zoneIds = new HashSet<>(zoneService.findZoneIdsByNameContaining(keyword));
        zoneIds.addAll(cameraQueryService.findZoneIdsByCameraNameContaining(keyword));
        List<Long> dongIds = regionLookupService.findDongIdsByNameContaining(keyword);
        if (!dongIds.isEmpty()) {
            zoneIds.addAll(zoneService.findAllZoneIdsInDongs(dongIds));
        }
        return zoneIds;
    }

    private static LocalDateTime toKst(OffsetDateTime time) {
        return time == null ? null : time.atZoneSameInstant(KST).toLocalDateTime();
    }
}
