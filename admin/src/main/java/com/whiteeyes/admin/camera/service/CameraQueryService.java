package com.whiteeyes.admin.camera.service;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BinaryOperator;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.whiteeyes.common.camera.entity.Camera;
import com.whiteeyes.common.camera.entity.CameraSourceType;
import com.whiteeyes.common.camera.repository.CameraRepository;

// TODO: 캠 가동·수정 기능에서 일반 CameraService가 필요해지면 합친다(지금 dev에는 CameraRegisterService만 있음)
/** 분석 결과 수신(FNC-A-024) */
@Service
@RequiredArgsConstructor
public class CameraQueryService {

    // 다른 기능(분석 결과 수신, 이벤트 목록, 녹화 등록 등)이 캠 정보를 읽을 때 쓰는 조회 전용 창구.
    private final CameraRepository cameraRepository;

    /**
     * 분석 결과를 받을 캠(삭제되지 않았고 run_requested=1)만 골라 cameraId → zoneId로 돌려준다(FNC-A-024).
     */
    @Transactional(readOnly = true)
    public Map<Long, Long> findZoneIdsOfReceivingCameras(Set<Long> cameraIds) {
        return cameraRepository.findAllById(cameraIds).stream()
                // 삭제되지 않았고 가동 요청된 캠의 결과만 받는다. 정지한 캠에 늦게 도착한 결과는 버리기 위함.
                .filter(camera -> camera.getDeletedAt() == null && camera.isRunRequested())
                .collect(Collectors.toMap(Camera::getId, Camera::getZoneId));
    }

    /** 캠 번호 → 캠 이름. 삭제된 캠도 포함한다(이벤트 목록의 캠 이름, FNC-A-036). */
    @Transactional(readOnly = true)
    // 삭제된 캠도 과거 이벤트에 이름을 보여줘야 하므로 deletedAt으로 거르지 않는다.
    public Map<Long, String> findNamesByIds(Collection<Long> cameraIds) {
        return cameraRepository.findAllById(cameraIds).stream()
                .collect(Collectors.toMap(Camera::getId, Camera::getName));
    }

    /** 이름에 검색어가 들어 있는 캠의 구역 번호. 삭제된 캠도 포함한다(이벤트 목록 검색, FNC-A-036). */
    @Transactional(readOnly = true)
    public List<Long> findZoneIdsByCameraNameContaining(String keyword) {
        return cameraRepository.findZoneIdsByNameContaining(keyword);
    }

    /**
     * 구역 번호 → 그 구역 캠 중 가장 먼저 가동된 시각. 삭제된 캠도 포함한다(FNC-A-051 처리 순서 1).
     * 가동한 적이 있는 캠이 없는 구역은 빠진다.
     */
    @Transactional(readOnly = true)
    public Map<Long, LocalDateTime> findFirstStartedAtByZone() {
        return cameraRepository.findByFirstStartedAtIsNotNull().stream()
                .collect(Collectors.toMap(
                        // 같은 구역에 캠이 여러 번 등록됐을 수 있어(삭제 후 재등록) 키가 겹치면 더 이른 시각을 고른다.
                        Camera::getZoneId, Camera::getFirstStartedAt, BinaryOperator.minBy(LocalDateTime::compareTo)));
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
