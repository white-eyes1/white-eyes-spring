package com.whiteeyes.admin.camera.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClientException;

import com.whiteeyes.admin.camera.client.AnalyzerClient;
import com.whiteeyes.admin.camera.dto.CameraStatusUpdateRequest;
import com.whiteeyes.admin.camera.exception.CameraNotFoundException;
import com.whiteeyes.common.camera.entity.Camera;
import com.whiteeyes.common.camera.entity.CameraStatus;
import com.whiteeyes.common.camera.repository.CameraRepository;

@Service
public class CameraStatusService {

    private static final Logger log = LoggerFactory.getLogger(CameraStatusService.class);

    private final CameraRepository cameraRepository;
    private final AnalyzerClient analyzerClient;

    public CameraStatusService(CameraRepository cameraRepository, AnalyzerClient analyzerClient) {
        this.cameraRepository = cameraRepository;
        this.analyzerClient = analyzerClient;
    }

    @Transactional
    public void reportStatus(Long cameraId, CameraStatusUpdateRequest request) {
        Camera camera = cameraRepository
                .findByIdAndDeletedAtIsNull(cameraId)
                .orElseThrow(() -> new CameraNotFoundException(cameraId));

        // 정지를 원하는 캠의 RUNNING 보고는 무시하고 다시 정지 지시(엇갈림 정리, FNC-A-025)
        if (request.status() == CameraStatus.RUNNING && !camera.isRunRequested()) {
            stopMismatchedCamera(cameraId);
            return;
        }

        CameraStatus previousStatus = camera.getStatus();
        camera.updateStatus(request.status(), request.progress(), request.errorMessage());
        // 준비 중 진행률 보고는 자주 오므로 상태가 바뀔 때만 INFO
        if (previousStatus != request.status()) {
            log.info("캠 상태 변경 cameraId={} {} -> {}", cameraId, previousStatus, request.status());
        } else {
            log.debug("캠 상태 보고 cameraId={} status={} progress={}", cameraId, request.status(), request.progress());
        }
    }

    private void stopMismatchedCamera(Long cameraId) {
        try {
            analyzerClient.stopCamera(cameraId);
            log.info("정지 요청된 캠의 RUNNING 보고 무시, 정지 지시 cameraId={}", cameraId);
        } catch (RestClientException e) {
            // 분석 서버는 다음 상태 보고 때 다시 정리되므로 보고 자체는 성공으로 처리
            log.warn("엇갈린 캠 정지 지시 실패 cameraId={} reason={}", cameraId, e.getMessage());
        }
    }
}
