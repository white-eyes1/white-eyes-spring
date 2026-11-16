package com.whiteeyes.common.camera.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.whiteeyes.common.camera.entity.CameraStatus;
import com.whiteeyes.common.camera.repository.CameraRepository;

/** 다른 도메인(실시간 화면 권한 확인)이 캠이 지금 가동 중인지 물을 때 부르는 camera 도메인의 공개 service(FNC-A-041). */
@Service
@RequiredArgsConstructor
public class CameraRunningService {

    // 캠 저장소
    private final CameraRepository cameraRepository;

    /** 캠이 있고 삭제되지 않았으며 상태가 RUNNING인지. */
    @Transactional(readOnly = true)
    public boolean isRunning(Long cameraId) {
        return cameraRepository
                .findByIdAndDeletedAtIsNull(cameraId)
                // 캠이 있으면 상태만 비교하고, 없거나 삭제된 캠이면 아래에서 false가 된다
                .map(camera -> camera.getStatus() == CameraStatus.RUNNING)
                .orElse(false);
    }
}
