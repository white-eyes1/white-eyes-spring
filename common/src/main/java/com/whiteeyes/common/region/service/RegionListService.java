package com.whiteeyes.common.region.service;

import java.util.List;
import java.util.Set;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import com.whiteeyes.common.camera.service.ZoneCameraService;
import com.whiteeyes.common.region.dto.RegionResponse;
import com.whiteeyes.common.region.dto.ZoneResponse;
import com.whiteeyes.common.region.entity.Region;
import com.whiteeyes.common.region.entity.RegionLevel;
import com.whiteeyes.common.region.entity.Zone;
import com.whiteeyes.common.region.exception.RegionNotFoundException;
import com.whiteeyes.common.region.exception.ZoneNotFoundException;
import com.whiteeyes.common.region.repository.RegionRepository;
import com.whiteeyes.common.region.repository.ZoneRepository;

/** 시 → 구 → 동 → 구역 순으로 하위 목록과 경계를 내려준다(FNC-C-008). */
@Service
@RequiredArgsConstructor
public class RegionListService {

    // 지역 저장소
    private final RegionRepository regionRepository;
    // 구역 저장소
    private final ZoneRepository zoneRepository;
    // 구역에 캠이 있는지는 camera 도메인의 공개 서비스를 통해서만 묻는다(다른 도메인 저장소를 직접 쓰지 않는 규칙)
    private final ZoneCameraService zoneCameraService;
    // DB에 문자열로 저장된 경계(GeoJSON)를 JSON 트리로 바꾸는 도구
    private final JsonMapper jsonMapper;

    /**
     * parentId가 없으면 시·도 목록, 있으면 그 바로 아래 지역(코드 순). 읍·면·동을 주면 빈 목록이다.
     *
     * @throws RegionNotFoundException parentId의 지역이 없으면(404)
     */
    @Transactional(readOnly = true)
    public List<RegionResponse> findRegions(Long parentId) {
        // 부모가 없으면 시·도 목록, 있으면 그 아래 지역을 담는다
        List<Region> regions;
        if (parentId == null) {
            // 처리 순서 1
            regions = regionRepository.findByLevelOrderByCode(RegionLevel.SIDO);
        } else {
            // 처리 순서 2
            // 없는 지역 번호면 빈 목록이 아니라 404로 알린다
            requireRegion(parentId);
            regions = regionRepository.findByParent_IdOrderByCode(parentId);
        }
        // 처리 순서 4: 경계와 중심점을 함께 반환
        return regions.stream()
                // 경계 문자열을 JSON 객체로 바꿔 응답에 넣는다
                .map(region -> RegionResponse.of(region, jsonMapper.readTree(region.getBoundary())))
                .toList();
    }

    /**
     * dongId가 있으면 그 동의, 없으면 전체의 삭제되지 않은 구역(번호 순). 삭제된 구역은 뺀다(loca-005).
     *
     * @throws RegionNotFoundException dongId의 지역이 없으면(404)
     */
    @Transactional(readOnly = true)
    public List<ZoneResponse> findZones(Long dongId) {
        // 처리 순서 3
        // 동이 없으면 전체, 있으면 그 동의 구역을 담는다
        List<Zone> zones;
        if (dongId == null) {
            zones = zoneRepository.findByDeletedAtIsNullOrderById();
        } else {
            requireRegion(dongId);
            zones = zoneRepository.findByDongIdAndDeletedAtIsNullOrderById(dongId);
        }
        // 캠 유무를 구역마다 따로 묻지 않고, 모든 구역 번호를 모아 한 번에 묻는다
        Set<Long> zoneIdsWithCamera = zoneCameraService.findZoneIdsWithCamera(
                zones.stream().map(Zone::getId).toList());
        // 처리 순서 4
        return zones.stream()
                .map(zone -> ZoneResponse.of(
                        zone, jsonMapper.readTree(zone.getBoundary()), zoneIdsWithCamera.contains(zone.getId())))
                .toList();
    }

    /**
     * 구역 하나. 삭제된 구역도 준다(deleted=true). 지난 통계·다시보기 화면이 '(삭제됨)'을 붙이는 데 쓴다(#183).
     *
     * @throws ZoneNotFoundException 없는 구역이면(404 ZONE_NOT_FOUND)
     */
    @Transactional(readOnly = true)
    public ZoneResponse findZone(Long zoneId) {
        // findById라서 삭제 표시된 구역도 가져온다(응답의 deleted로 구분)
        Zone zone = zoneRepository.findById(zoneId).orElseThrow(ZoneNotFoundException::new);
        boolean hasCamera =
                !zoneCameraService.findZoneIdsWithCamera(List.of(zoneId)).isEmpty();
        return ZoneResponse.of(zone, jsonMapper.readTree(zone.getBoundary()), hasCamera);
    }

    /** 지역이 있는지 확인하고 없으면 404 예외를 던진다. */
    private void requireRegion(Long regionId) {
        if (!regionRepository.existsById(regionId)) {
            throw new RegionNotFoundException();
        }
    }
}
