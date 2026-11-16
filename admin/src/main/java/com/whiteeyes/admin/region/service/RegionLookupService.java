package com.whiteeyes.admin.region.service;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.whiteeyes.admin.region.dto.RegionNames;
import com.whiteeyes.common.region.entity.Region;
import com.whiteeyes.common.region.entity.RegionLevel;
import com.whiteeyes.common.region.exception.RegionNotFoundException;
import com.whiteeyes.common.region.repository.RegionRepository;
import com.whiteeyes.common.region.service.RegionQueryService;

/** 다른 도메인(캠 목록)이 지역을 조회할 때 부르는 지역 도메인의 공개 service. */
@Service
@RequiredArgsConstructor
public class RegionLookupService {

    // 지역 조회용 저장소(이 도메인이 직접 소유)
    private final RegionRepository regionRepository;
    // 지도 현황(common)과 지역 경로 표기를 같은 기준으로 맞추기 위해 common의 조회 service를 재사용한다
    private final RegionQueryService regionQueryService;

    /** 지역(시·도, 시·군·구, 읍·면·동 아무거나) 아래의 읍·면·동 번호. 읍·면·동을 주면 자기 자신이다. */
    @Transactional(readOnly = true)
    public List<Long> findDongIdsUnder(Long regionId) {
        // 어느 단계의 지역이든 그 아래에 있는 읍·면·동(EMD) 단계의 번호만 모은다
        return regionRepository.findIdsOfLevelUnder(regionId, RegionLevel.EMD);
    }

    /**
     * 지역이 있는지 확인하고, 있으면 그 아래 읍·면·동 번호를 돌려준다(이벤트 목록 지역 필터, FNC-A-036).
     *
     * @throws com.whiteeyes.common.region.exception.RegionNotFoundException 없는 지역이면(404 REGION_NOT_FOUND)
     */
    @Transactional(readOnly = true)
    public List<Long> requireDongIdsUnder(Long regionId) {
        // 먼저 존재를 확인해, 없는 지역(404)과 '아래에 동이 하나도 없는 지역(빈 목록)'을 구분한다
        if (!regionRepository.existsById(regionId)) {
            throw new RegionNotFoundException();
        }
        return findDongIdsUnder(regionId);
    }

    /** 동 이름이나 소속 시·군·구 이름에 검색어가 들어 있는 읍·면·동 번호(이벤트 목록 검색, FNC-A-036). */
    @Transactional(readOnly = true)
    public List<Long> findDongIdsByNameContaining(String keyword) {
        // 동 이름 자체나 그 동의 부모(시·군·구) 이름에 검색어가 들어 있으면 포함한다
        return regionRepository.findIdsOfLevelByNameOrParentNameContaining(keyword, RegionLevel.EMD);
    }

    /** 읍·면·동 번호 → 지역 경로("유성구 · 궁동"). 지도 현황(common)과 같은 기준을 쓰도록 RegionQueryService에 맡긴다. */
    @Transactional(readOnly = true)
    public Map<Long, String> findPaths(Collection<Long> dongIds) {
        // 경로 문자열 규칙을 한 곳(common)에만 두려고 그대로 위임한다
        return regionQueryService.findPaths(dongIds);
    }

    /** 읍·면·동 번호 → 시·도 / 시·군·구 / 읍·면·동 이름(이벤트 엑셀 FNC-A-039). 상위 두 단계까지 쿼리 한 번으로 가져온다. */
    @Transactional(readOnly = true)
    public Map<Long, RegionNames> findNames(Collection<Long> dongIds) {
        // 동과 그 부모·조부모(시·군·구, 시·도)를 한 번에 가져온다. 동마다 따로 조회하면 쿼리가 N번 나가기 때문이다
        return regionRepository.findAllWithAncestorsByIdIn(dongIds).stream()
                // 같은 동 번호가 겹쳐 와도 예외 없이 먼저 것을 쓰도록 병합 함수를 둔다
                .collect(Collectors.toMap(Region::getId, this::namesOf, (first, second) -> first));
    }

    /** 지역(시·도, 시·군·구, 읍·면·동 아무거나)의 이름. 없는 지역이면 null이다. */
    @Transactional(readOnly = true)
    public String findName(Long regionId) {
        // 지역이 없으면 예외 대신 null을 돌려준다(호출한 쪽이 '이름 없음'으로 처리)
        return regionRepository.findById(regionId).map(Region::getName).orElse(null);
    }

    /** 읍·면·동 한 곳에서 부모를 타고 올라가 시·도, 시·군·구, 읍·면·동 이름을 모은다. 상위 지역이 없으면 해당 이름은 null이다. */
    private RegionNames namesOf(Region dong) {
        Region sigungu = dong.getParent();
        Region sido = sigungu == null ? null : sigungu.getParent();
        return new RegionNames(
                sido == null ? null : sido.getName(), sigungu == null ? null : sigungu.getName(), dong.getName());
    }
}
