package com.whiteeyes.admin.camera.service;

import com.whiteeyes.admin.camera.dto.CameraListItem;
import com.whiteeyes.admin.region.service.RegionLookupService;
import com.whiteeyes.admin.region.dto.ZoneSummary;
import com.whiteeyes.admin.region.service.ZoneService;
import com.whiteeyes.common.camera.entity.Camera;
import com.whiteeyes.common.camera.entity.CameraStatus;
import com.whiteeyes.common.camera.repository.CameraRepository;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 모든 캠의 상태·진행률과 구역 정보를 한 번에 모아 준다(FNC-A-022 캠 목록). */
@Service
@RequiredArgsConstructor
public class CameraListService {

    private final CameraRepository cameraRepository;
    private final ZoneService zoneService;
    private final RegionLookupService regionLookupService;

    /**
     * 삭제되지 않은 캠 목록을 번호 순으로 돌려준다. regionId가 있으면 그 지역 아래 구역의 캠만 돌려준다.
     * 캠마다 따로 조회하지 않고 캠 → 구역 → 지역 경로를 각각 한 번의 쿼리로 모은다.
     */
    @Transactional(readOnly = true)
    public List<CameraListItem> list(Long regionId) {
        List<Camera> cameras = regionId == null ? cameraRepository.findByDeletedAtIsNullOrderByIdAsc() : findInRegion(regionId);
        if (cameras.isEmpty()) {
            return List.of();
        }

        Set<Long> zoneIds = cameras.stream().map(Camera::getZoneId).collect(Collectors.toSet());
        Map<Long, ZoneSummary> zones = zoneService.findSummaries(zoneIds);
        Set<Long> dongIds = zones.values().stream().map(ZoneSummary::dongId).collect(Collectors.toSet());
        Map<Long, String> regionPaths = regionLookupService.findPaths(dongIds);

        return cameras.stream()
                .map(camera -> toItem(camera, zones.get(camera.getZoneId()), regionPaths))
                .toList();
    }

    private List<Camera> findInRegion(Long regionId) {
        List<Long> dongIds = regionLookupService.findDongIdsUnder(regionId);
        if (dongIds.isEmpty()) {
            return List.of();
        }
        List<Long> zoneIds = zoneService.findActiveZoneIdsInDongs(dongIds);
        if (zoneIds.isEmpty()) {
            return List.of();
        }
        return cameraRepository.findByZoneIdInAndDeletedAtIsNullOrderByIdAsc(zoneIds);
    }

    private CameraListItem toItem(Camera camera, ZoneSummary zone, Map<Long, String> regionPaths) {
        // stat·todayEventCount·recent10m은 1분 통계·이벤트가 들어오면 여기서 채운다(지금은 null · 0 · 빈 목록)
        return new CameraListItem(
                camera.getId(),
                camera.getZoneId(),
                zone == null ? null : zone.name(),
                zone == null ? null : regionPaths.get(zone.dongId()),
                zone == null ? 0 : zone.areaM2().doubleValue(),
                camera.getName(),
                camera.getSourceType(),
                camera.getSourceUri(),
                camera.getStatus(),
                camera.getStatus() == CameraStatus.PREPARING ? camera.getProgress() : null,
                camera.getErrorMessage(),
                camera.isRunRequested(),
                null,
                0,
                List.of());
    }
}
