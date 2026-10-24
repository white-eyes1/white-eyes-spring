package com.whiteeyes.common.camera.service;

import com.whiteeyes.common.camera.entity.Camera;
import com.whiteeyes.common.camera.entity.CameraStatus;
import com.whiteeyes.common.camera.repository.CameraRepository;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 다른 도메인(캠 이상 감지, FNC-A-035)이 감시할 캠을 물을 때 부르는 camera 도메인의 공개 service. */
@Service
@RequiredArgsConstructor
public class WatchedCameraService {

    private final CameraRepository cameraRepository;

    /** 감시 대상 캠의 값. 다른 도메인에 엔티티를 넘기지 않으려고 필요한 것만 담는다. */
    public record WatchedCamera(Long cameraId, Long zoneId, CameraStatus status) {}

    /** 감시 대상: run_requested=1이고 삭제되지 않은 캠(FNC-A-035). */
    @Transactional(readOnly = true)
    public List<WatchedCamera> findWatchedCameras() {
        return cameraRepository.findByRunRequestedTrueAndDeletedAtIsNullOrderByIdAsc().stream()
                .map(WatchedCameraService::toWatched)
                .toList();
    }

    /** 감시 대상이면 그 캠을, 아니면(없음·삭제·run_requested=0) 빈 값(FNC-A-035). */
    @Transactional(readOnly = true)
    public Optional<WatchedCamera> findWatchedCamera(Long cameraId) {
        return cameraRepository
                .findByIdAndDeletedAtIsNull(cameraId)
                .filter(Camera::isRunRequested)
                .map(WatchedCameraService::toWatched);
    }

    private static WatchedCamera toWatched(Camera camera) {
        return new WatchedCamera(camera.getId(), camera.getZoneId(), camera.getStatus());
    }
}
