package com.whiteeyes.common.region.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.whiteeyes.common.region.entity.Region;
import com.whiteeyes.common.region.entity.RegionLevel;
import com.whiteeyes.common.region.exception.RegionNotFoundException;
import com.whiteeyes.common.region.repository.RegionRepository;

/**
 * 다른 도메인(지도 현황 등)에 지역 계층을 내주는 조회 서비스(FNC-C-009).
 */
// TODO(#8): 지역·구역 목록(FNC-C-008)에서 지역 서비스가 생기면 그쪽으로 옮긴다
@Service
public class RegionQueryService {

    private final RegionRepository regionRepository;

    public RegionQueryService(RegionRepository regionRepository) {
        this.regionRepository = regionRepository;
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
}
