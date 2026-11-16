package com.whiteeyes.common.region.service;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.whiteeyes.common.region.entity.Region;
import com.whiteeyes.common.region.entity.RegionLevel;
import com.whiteeyes.common.region.entity.Zone;
import com.whiteeyes.common.region.exception.RegionNotFoundException;
import com.whiteeyes.common.region.exception.ZoneNotFoundException;
import com.whiteeyes.common.region.repository.RegionRepository;
import com.whiteeyes.common.region.repository.ZoneRepository;

/**
 * 다른 도메인(지도 현황 등)에 지역 계층과 구역을 내주는 조회 서비스(FNC-C-009).
 */
@Service
@RequiredArgsConstructor
public class RegionQueryService {

    // 지역 경로 문자열의 구분자("유성구 · 궁동")
    private static final String PATH_SEPARATOR = " · ";

    // 지역 저장소. 다른 도메인에서는 이 서비스로만 지역과 구역을 읽는다
    private final RegionRepository regionRepository;
    // 구역 저장소
    private final ZoneRepository zoneRepository;

    /**
     * @throws RegionNotFoundException 없는 지역이면(404)
     */
    @Transactional(readOnly = true)
    /** 지역 하나를 읽는다. */
    public Region getRegion(long regionId) {
        return regionRepository.findById(regionId).orElseThrow(RegionNotFoundException::new);
    }

    /** 바로 아래 지역들(코드 순). 읍·면·동이면 빈 목록. */
    @Transactional(readOnly = true)
    // 읍·면·동은 아래 단계가 없어 빈 목록이 나온다
    public List<Region> findChildRegions(long regionId) {
        return regionRepository.findByParent_IdOrderByCode(regionId);
    }

    /** 지역 아래의 모든 읍·면·동. 읍·면·동이면 자기 자신. */
    @Transactional(readOnly = true)
    public List<Region> findEmdsUnder(Region region) {
        // 행정구역 코드는 상위 코드로 시작하므로, 코드가 이 지역 코드로 시작하는 읍·면·동을 모으면 아래 전부다. 읍·면·동 자신의 코드는 자기 자신으로 시작해 자기만 나온다
        return regionRepository.findByLevelAndCodeStartingWith(RegionLevel.EMD, region.getCode());
    }

    /** 읍·면·동들에 속한 삭제되지 않은 구역(삭제된 구역은 목록·지도에서 뺀다, tables.md zone). */
    @Transactional(readOnly = true)
    public List<Zone> findZonesInDongs(Collection<Long> dongIds) {
        return zoneRepository.findByDongIdInAndDeletedAtIsNull(dongIds);
    }

    /**
     * 삭제되지 않은 구역. 삭제된 구역은 목록·지도에 없으므로 없는 구역과 같이 본다.
     *
     * @throws ZoneNotFoundException 없거나 삭제된 구역이면(404 ZONE_NOT_FOUND)
     */
    @Transactional(readOnly = true)
    public Zone getActiveZone(long zoneId) {
        return zoneRepository
                .findById(zoneId)
                // 삭제된 구역은 없는 것으로 취급한다
                .filter(zone -> zone.getDeletedAt() == null)
                .orElseThrow(ZoneNotFoundException::new);
    }

    /**
     * 삭제된 구역도 돌려주는 구역 조회. 지난 통계·다시보기 화면이 삭제된 구역의 이름을 '(삭제됨)'과 함께 보일 때 쓴다(FNC-A-017 업무 규칙).
     *
     * @throws ZoneNotFoundException 없는 구역이면(404 ZONE_NOT_FOUND)
     */
    @Transactional(readOnly = true)
    public Zone getZoneIncludingDeleted(long zoneId) {
        return zoneRepository.findById(zoneId).orElseThrow(ZoneNotFoundException::new);
    }

    /** 읍·면·동들에 속한 구역. 삭제된 구역도 포함한다(지난 통계의 지역 합산, FNC-C-012). */
    @Transactional(readOnly = true)
    public List<Zone> findAllZonesInDongs(Collection<Long> dongIds) {
        return zoneRepository.findByDongIdIn(dongIds);
    }

    /** 읍·면·동들에 속한 구역 id. 삭제된 구역도 포함한다(오늘 기록 등 지난 값, FNC-C-016). */
    @Transactional(readOnly = true)
    public List<Long> findAllZoneIdsInDongs(Collection<Long> dongIds) {
        return zoneRepository.findByDongIdIn(dongIds).stream().map(Zone::getId).toList();
    }

    /** 읍·면·동 번호 → 지역 경로("유성구 · 궁동"). 상위(시·군·구)까지 쿼리 한 번으로 가져온다(지도 구역 핀 툴팁, 캠·이벤트 목록). */
    @Transactional(readOnly = true)
    public Map<Long, String> findPaths(Collection<Long> dongIds) {
        // 부모까지 한 번에 읽은 지역으로 "상위 · 동" 문자열을 만든다
        return regionRepository.findAllWithParentByIdIn(dongIds).stream()
                // 키(지역 번호)가 중복되면 예외 대신 먼저 나온 값을 쓴다
                .collect(Collectors.toMap(Region::getId, RegionQueryService::pathOf, (first, second) -> first));
    }

    /** 상위 지역이 있으면 "상위 · 이름", 없으면 이름만 돌려준다. */
    private static String pathOf(Region dong) {
        Region parent = dong.getParent();
        return parent == null ? dong.getName() : parent.getName() + PATH_SEPARATOR + dong.getName();
    }
}
