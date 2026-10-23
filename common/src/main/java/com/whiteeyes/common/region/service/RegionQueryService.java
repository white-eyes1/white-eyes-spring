package com.whiteeyes.common.region.service;

import java.util.Collection;
import java.util.List;

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
public class RegionQueryService {

    private final RegionRepository regionRepository;
    private final ZoneRepository zoneRepository;

    public RegionQueryService(RegionRepository regionRepository, ZoneRepository zoneRepository) {
        this.regionRepository = regionRepository;
        this.zoneRepository = zoneRepository;
    }

    /**
     * @throws RegionNotFoundException 없는 지역이면(404)
     */
    @Transactional(readOnly = true)
    public Region getRegion(long regionId) {
        return regionRepository.findById(regionId).orElseThrow(RegionNotFoundException::new);
    }

    /** 바로 아래 지역들(코드 순). 읍·면·동이면 빈 목록. */
    @Transactional(readOnly = true)
    public List<Region> findChildRegions(long regionId) {
        return regionRepository.findByParent_IdOrderByCode(regionId);
    }

    /** 지역 아래의 모든 읍·면·동. 읍·면·동이면 자기 자신. */
    @Transactional(readOnly = true)
    public List<Region> findEmdsUnder(Region region) {
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
        return zoneRepository.findById(zoneId)
                .filter(zone -> zone.getDeletedAt() == null)
                .orElseThrow(ZoneNotFoundException::new);
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
}
