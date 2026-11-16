package com.whiteeyes.common.camera.service;

import java.util.Collection;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.whiteeyes.common.camera.entity.CameraStatus;
import com.whiteeyes.common.camera.repository.CameraRepository;

/**
 * 다른 도메인(현재 현황 요약 등)에 캠 수를 내주는 조회 서비스(FNC-C-016).
 */
// 이름을 CameraQueryService로 하지 않은 이유: #16~#18의 admin CameraQueryService와 빈 이름이 같아 함께 머지되면 앱이 뜨지 않는다
@Service
@RequiredArgsConstructor
public class CameraCountService {

    // 캠 도메인의 저장소. 다른 도메인은 저장소가 아니라 이 서비스를 통해서만 캠 수를 묻는다
    private final CameraRepository cameraRepository;

    /** 구역들의 삭제되지 않은 캠 수. */
    @Transactional(readOnly = true)
    public int countCameras(Collection<Long> zoneIds) {
        // 구역이 하나도 없으면 IN () 쿼리를 보내지 않고 0으로 끝낸다
        return zoneIds.isEmpty() ? 0 : (int) cameraRepository.countByZoneIdInAndDeletedAtIsNull(zoneIds);
    }

    /** 구역들의 삭제되지 않은 캠 중 가동 중(RUNNING)인 캠 수. */
    @Transactional(readOnly = true)
    public int countRunningCameras(Collection<Long> zoneIds) {
        // 위와 같은 이유로 빈 목록은 쿼리 없이 0. 아니면 RUNNING 상태 캠만 센다
        return zoneIds.isEmpty()
                ? 0
                : (int) cameraRepository.countByZoneIdInAndDeletedAtIsNullAndStatus(zoneIds, CameraStatus.RUNNING);
    }
}
