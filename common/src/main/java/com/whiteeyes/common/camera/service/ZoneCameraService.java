package com.whiteeyes.common.camera.service;

import com.whiteeyes.common.camera.repository.CameraRepository;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 다른 도메인(지역·구역 목록)이 구역에 캠이 있는지 물을 때 부르는 camera 도메인의 공개 service(FNC-C-008). */
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
}
