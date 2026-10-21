package com.whiteeyes.admin.region.service;

import com.whiteeyes.common.region.entity.Region;
import com.whiteeyes.common.region.entity.RegionLevel;
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
