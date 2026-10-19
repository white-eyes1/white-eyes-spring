package com.whiteeyes.admin.camera.service;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.whiteeyes.common.camera.entity.Camera;
import com.whiteeyes.common.camera.repository.CameraRepository;

// TODO(#11): 캠 등록(FNC-A-018)에서 CameraService가 생기면 그쪽으로 옮긴다
@Service
public class CameraQueryService {

    private final CameraRepository cameraRepository;

    public CameraQueryService(CameraRepository cameraRepository) {
        this.cameraRepository = cameraRepository;
    }

    /**
     * 분석 결과를 받을 캠(삭제되지 않았고 run_requested=1)만 골라 cameraId → zoneId로 돌려준다(FNC-A-024).
     */
    @Transactional(readOnly = true)
    public Map<Long, Long> findZoneIdsOfReceivingCameras(Set<Long> cameraIds) {
        return cameraRepository.findAllById(cameraIds).stream()
                .filter(camera -> camera.getDeletedAt() == null && camera.isRunRequested())
                .collect(Collectors.toMap(Camera::getId, Camera::getZoneId));
    }
}
