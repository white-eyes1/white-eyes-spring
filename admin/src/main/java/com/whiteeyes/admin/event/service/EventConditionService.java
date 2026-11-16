package com.whiteeyes.admin.event.service;

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

import com.whiteeyes.admin.camera.service.CameraQueryService;
import com.whiteeyes.admin.event.dto.EventSearchRequest;
import com.whiteeyes.admin.region.service.RegionLookupService;
import com.whiteeyes.admin.region.service.ZoneService;
import com.whiteeyes.common.event.entity.Event;
import com.whiteeyes.common.event.repository.EventSpecifications;
import com.whiteeyes.common.exception.BusinessException;
import com.whiteeyes.common.exception.ErrorCode;

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

    // 요청 시각(오프셋 포함)을 DB가 쓰는 한국 시각으로 바꿀 때 쓴다
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    // 다른 도메인의 service를 통해 구역·지역·캠 이름을 찾는다(엔티티·저장소를 직접 부르지 않는다)
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
        // 잘못된 조합이면 여기서 예외를 던져 조회까지 가지 않게 한다
        validate(request);

        // null은 '구역 조건 없음', 빈 집합은 '맞는 구역이 없음'을 뜻한다
        Set<Long> zoneIds = resolveZoneIds(request);
        // 맞는 구역이 없으면 쿼리를 돌릴 필요 없이 결과가 없다는 신호(빈 Optional)를 돌려준다
        if (zoneIds != null && zoneIds.isEmpty()) {
            return Optional.empty();
        }

        // 조건을 하나씩 모아 마지막에 AND로 묶는다
        List<Specification<Event>> conditions = new ArrayList<>();
        // overlap=true면 기간과 겹치는 이벤트(발생이 기간 앞이어도 이어진 것 포함), 아니면 발생 시각이 기간 안인 것(명세 기본)
        conditions.add(
                request.overlap()
                        ? EventSpecifications.overlapping(toKst(request.from()), toKst(request.to()))
                        : EventSpecifications.startedBetween(toKst(request.from()), toKst(request.to())));
        // 종류 조건(비어 있으면 거르지 않음)
        conditions.add(EventSpecifications.ofTypes(request.types()));
        // 구역 조건이 있을 때만 추가
        if (zoneIds != null) {
            conditions.add(EventSpecifications.inZones(zoneIds));
        }
        // 모든 조건을 AND로 합친다
        return Optional.of(Specification.allOf(conditions));
    }

    /** 입력 조합을 검사한다. 위반하면 400 INVALID_INPUT. */
    private void validate(EventSearchRequest request) {
        // 지역과 구역은 서로 다른 범위 지정 방식이라 함께 받으면 의미가 모호하다
        if (request.regionId() != null && request.zoneId() != null) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "지역과 구역은 함께 지정할 수 없습니다.");
        }
        // 기간의 시작은 끝보다 앞이어야 한다(같아도 안 됨: 끝은 '미만'이라 빈 기간이 된다)
        if (request.from() != null && request.to() != null && !request.from().isBefore(request.to())) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "시작 시각은 끝 시각보다 앞이어야 합니다.");
        }
    }

    /** 지역·구역·검색어가 모두 비어 있으면 null(구역으로는 거르지 않음), 아니면 맞는 구역 번호들. */
    // 위쪽 설명 참고. 구역·지역 조건으로 먼저 범위를 정하고, 검색어가 있으면 그 범위와 교집합을 낸다
    private Set<Long> resolveZoneIds(EventSearchRequest request) {
        // null = 아직 구역으로 좁히지 않음
        Set<Long> scoped = null;
        // 구역을 직접 지정
        if (request.zoneId() != null) {
            scoped = Set.of(request.zoneId());
            // 지역 지정: 그 지역 아래의 모든 읍·면·동을 찾고(없는 지역이면 404), 그 동들에 속한 구역 번호를 모은다
        } else if (request.regionId() != null) {
            List<Long> dongIds = regionLookupService.requireDongIdsUnder(request.regionId());
            scoped = dongIds.isEmpty() ? Set.of() : new HashSet<>(zoneService.findAllZoneIdsInDongs(dongIds));
        }
        // 검색어가 없으면 구역·지역 조건만으로 끝
        if (request.keyword() == null) {
            return scoped;
        }
        // 검색어에 맞는 구역 번호
        Set<Long> matched = findZoneIdsByKeyword(request.keyword());
        if (scoped != null) {
            // 구역·지역 조건도 있으면 둘 다 만족하는 것만 남긴다
            matched.retainAll(scoped);
        }
        return matched;
    }

    /** 구역 이름, 캠 이름, 소속 구·동 이름 중 하나라도 검색어가 들어 있는 구역(삭제된 구역·캠 포함). */
    // 세 가지 이름(구역·캠·구/동)을 각각 찾아 합집합으로 모은다
    private Set<Long> findZoneIdsByKeyword(String keyword) {
        Set<Long> zoneIds = new HashSet<>(zoneService.findZoneIdsByNameContaining(keyword));
        zoneIds.addAll(cameraQueryService.findZoneIdsByCameraNameContaining(keyword));
        // 구·동 이름이 맞으면 그 동에 속한 구역도 포함한다
        List<Long> dongIds = regionLookupService.findDongIdsByNameContaining(keyword);
        if (!dongIds.isEmpty()) {
            zoneIds.addAll(zoneService.findAllZoneIdsInDongs(dongIds));
        }
        return zoneIds;
    }

    // 요청의 오프셋 시각을 KST 벽시계 시각으로 바꾼다(DB에는 오프셋 없이 KST로 저장되어 있다). null은 그대로 null
    private static LocalDateTime toKst(OffsetDateTime time) {
        return time == null ? null : time.atZoneSameInstant(KST).toLocalDateTime();
    }
}
