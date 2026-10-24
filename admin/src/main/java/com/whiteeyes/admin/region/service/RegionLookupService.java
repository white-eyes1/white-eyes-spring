package com.whiteeyes.admin.region.service;

import com.whiteeyes.common.region.entity.Region;
import com.whiteeyes.common.region.entity.RegionLevel;
import com.whiteeyes.common.region.exception.RegionNotFoundException;
import com.whiteeyes.common.region.repository.RegionRepository;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 다른 도메인(캠 목록)이 지역을 조회할 때 부르는 지역 도메인의 공개 service. */
@Service
@RequiredArgsConstructor
public class RegionLookupService {

    private static final String PATH_SEPARATOR = " · ";

    private final RegionRepository regionRepository;

    /** 지역(시·도, 시·군·구, 읍·면·동 아무거나) 아래의 읍·면·동 번호. 읍·면·동을 주면 자기 자신이다. */
    @Transactional(readOnly = true)
    public List<Long> findDongIdsUnder(Long regionId) {
        return regionRepository.findIdsOfLevelUnder(regionId, RegionLevel.EMD);
    }

    /**
     * 지역이 있는지 확인하고, 있으면 그 아래 읍·면·동 번호를 돌려준다(이벤트 목록 지역 필터, FNC-A-036).
     *
     * @throws com.whiteeyes.common.region.exception.RegionNotFoundException 없는 지역이면(404 REGION_NOT_FOUND)
     */
    @Transactional(readOnly = true)
    public List<Long> requireDongIdsUnder(Long regionId) {
        if (!regionRepository.existsById(regionId)) {
            throw new RegionNotFoundException();
        }
        return findDongIdsUnder(regionId);
    }

    /** 동 이름이나 소속 시·군·구 이름에 검색어가 들어 있는 읍·면·동 번호(이벤트 목록 검색, FNC-A-036). */
    @Transactional(readOnly = true)
    public List<Long> findDongIdsByNameContaining(String keyword) {
        return regionRepository.findIdsOfLevelByNameOrParentNameContaining(keyword, RegionLevel.EMD);
    }

    /** 읍·면·동 번호 → 지역 경로("유성구 · 궁동"). 상위(시·군·구)까지 쿼리 한 번으로 가져온다. */
    @Transactional(readOnly = true)
    public Map<Long, String> findPaths(Collection<Long> dongIds) {
        return regionRepository.findAllWithParentByIdIn(dongIds).stream()
                .collect(Collectors.toMap(Region::getId, this::pathOf, (first, second) -> first));
    }

    private String pathOf(Region dong) {
        Region parent = dong.getParent();
        return parent == null ? dong.getName() : parent.getName() + PATH_SEPARATOR + dong.getName();
    }
}
