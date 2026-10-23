package com.whiteeyes.admin.camera.service;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.whiteeyes.common.camera.entity.Camera;
import com.whiteeyes.common.camera.entity.CameraSourceType;
import com.whiteeyes.common.camera.repository.CameraRepository;

// TODO: 캠 가동·수정 기능에서 일반 CameraService가 필요해지면 합친다(지금 dev에는 CameraRegisterService만 있음)
/** 분석 결과 수신(FNC-A-024) */
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

    /** 캠 번호 → 캠 이름. 삭제된 캠도 포함한다(이벤트 목록의 캠 이름, FNC-A-036). */
    @Transactional(readOnly = true)
    public Map<Long, String> findNamesByIds(Collection<Long> cameraIds) {
        return cameraRepository.findAllById(cameraIds).stream()
                .collect(Collectors.toMap(Camera::getId, Camera::getName));
    }

    /**
     * 구역 번호 → 그 구역의 캠 이름. 삭제된 캠도 포함하고, 캠이 여럿이면 가장 최근에 만든 것이다(이벤트 목록의 캠 이름, FNC-A-036).
     * 캠이 없는 구역은 빠진다.
     */
    @Transactional(readOnly = true)
    public Map<Long, String> findLatestNamesByZoneIds(Collection<Long> zoneIds) {
        return cameraRepository.findByZoneIdInOrderByIdAsc(zoneIds).stream()
                .collect(Collectors.toMap(Camera::getZoneId, Camera::getName, (older, newer) -> newer));
    }

    /** 이름에 검색어가 들어 있는 캠의 구역 번호. 삭제된 캠도 포함한다(이벤트 목록 검색, FNC-A-036). */
    @Transactional(readOnly = true)
    public List<Long> findZoneIdsByCameraNameContaining(String keyword) {
        return cameraRepository.findZoneIdsByNameContaining(keyword);
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
