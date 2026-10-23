package com.whiteeyes.admin.camera.service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.whiteeyes.common.camera.entity.Camera;
import com.whiteeyes.common.camera.entity.CameraSourceType;
import com.whiteeyes.common.camera.entity.CameraStatus;
import com.whiteeyes.common.camera.repository.CameraRepository;

// TODO: 캠 가동·수정 기능에서 일반 CameraService가 필요해지면 합친다(지금 dev에는 CameraRegisterService만 있음)
/** 분석 결과 수신(FNC-A-024) */
@Service
public class CameraQueryService {

    private final CameraRepository cameraRepository;

    /** 캠 이상 감지(FNC-A-035)가 보는 캠의 값. 다른 도메인에 엔티티를 넘기지 않으려고 필요한 것만 담는다. */
    public record WatchedCamera(Long cameraId, Long zoneId, CameraStatus status) {}

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

    /** 캠 이상 감지 대상: run_requested=1이고 삭제되지 않은 캠(FNC-A-035). */
    @Transactional(readOnly = true)
    public List<WatchedCamera> findWatchedCameras() {
        return cameraRepository.findByRunRequestedTrueAndDeletedAtIsNullOrderByIdAsc().stream()
                .map(CameraQueryService::toWatched)
                .toList();
    }

    /** 감시 대상이면 그 캠을, 아니면(없음·삭제·run_requested=0) 빈 값(FNC-A-035). */
    @Transactional(readOnly = true)
    public Optional<WatchedCamera> findWatchedCamera(Long cameraId) {
        return cameraRepository
                .findByIdAndDeletedAtIsNull(cameraId)
                .filter(Camera::isRunRequested)
                .map(CameraQueryService::toWatched);
    }

    private static WatchedCamera toWatched(Camera camera) {
        return new WatchedCamera(camera.getId(), camera.getZoneId(), camera.getStatus());
    }

    /**
     * 삭제되지 않은 캠의 입력 방식(LIVE·DUMMY)을 돌려준다. 없거나 삭제된 캠이면 빈 값(FNC-A-026).
     * 정지된 캠(run_requested=0)도 돌려준다 — 라이브 캠은 정지한 뒤에 마지막 녹화를 등록하기 때문(FNC-A-021).
     */
    @Transactional(readOnly = true)
    public Optional<CameraSourceType> findSourceTypeOfExistingCamera(Long cameraId) {
        return cameraRepository.findByIdAndDeletedAtIsNull(cameraId).map(Camera::getSourceType);
    }
}
