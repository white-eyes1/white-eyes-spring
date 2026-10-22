package com.whiteeyes.common.camera.service;

import com.whiteeyes.common.camera.repository.CameraRepository;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 다른 도메인(지역·구역 목록, 다시보기)이 구역의 캠을 물을 때 부르는 camera 도메인의 공개 service(FNC-C-008, FNC-A-043). */
@Service
@RequiredArgsConstructor
public class ZoneCameraService {

    private final CameraRepository cameraRepository;

    /** 주어진 구역 중 삭제되지 않은 캠이 있는 구역 번호. 쿼리 한 번으로 구한다. */
    @Transactional(readOnly = true)
    public Set<Long> findZoneIdsWithCamera(Collection<Long> zoneIds) {
        if (zoneIds.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(cameraRepository.findZoneIdsWithActiveCamera(zoneIds));
    }

    /** 그 구역에 있었던 모든 캠 번호. 삭제된 캠도 포함한다(다시보기 녹화 찾기, FNC-A-043). */
    @Transactional(readOnly = true)
    public List<Long> findCameraIdsInZone(Long zoneId) {
        return cameraRepository.findIdsByZoneId(zoneId);
    }
}
