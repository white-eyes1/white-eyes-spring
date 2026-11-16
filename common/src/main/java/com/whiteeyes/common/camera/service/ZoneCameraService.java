package com.whiteeyes.common.camera.service;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.whiteeyes.common.camera.entity.CameraStatus;
import com.whiteeyes.common.camera.repository.CameraRepository;

/** 다른 도메인(지역·구역 목록, 다시보기, 구역 삭제)이 구역의 캠을 묻거나 바꿀 때 부르는 camera 도메인의 공개 service(FNC-C-008, FNC-A-043, FNC-A-017). */
@Service
@RequiredArgsConstructor
public class ZoneCameraService {

    // 캠 저장소. 구역(region 도메인)·다시보기 쪽은 이 서비스를 통해서만 캠을 다룬다
    private final CameraRepository cameraRepository;

    /** 주어진 구역 중 삭제되지 않은 캠이 있는 구역 번호. 쿼리 한 번으로 구한다. */
    @Transactional(readOnly = true)
    public Set<Long> findZoneIdsWithCamera(Collection<Long> zoneIds) {
        // 빈 목록으로 IN 쿼리를 보내지 않는다
        if (zoneIds.isEmpty()) {
            return Set.of();
        }
        // 구역마다 쿼리하지 않고 한 번에 구해 N+1 쿼리를 피한다. 포함 여부 조회가 빠르도록 Set으로 돌려준다
        return new HashSet<>(cameraRepository.findZoneIdsWithActiveCamera(zoneIds));
    }

    /** 그 구역에 있었던 모든 캠 번호. 삭제된 캠도 포함한다(다시보기 녹화 찾기, FNC-A-043). */
    @Transactional(readOnly = true)
    public List<Long> findCameraIdsInZone(Long zoneId) {
        return cameraRepository.findIdsByZoneId(zoneId);
    }

    /** 그 구역에 준비 중(PREPARING)·가동 중(RUNNING)이거나 가동 요청(run_requested=1) 상태인 삭제되지 않은 캠이 있는지(구역 삭제 FNC-A-017 처리 순서 1). */
    @Transactional(readOnly = true)
    public boolean hasStartingOrRunningCamera(Long zoneId) {
        // 준비 중·가동 중 상태이거나 가동 요청이 켜진 캠이 있으면 구역을 지울 수 없다고 판단하는 근거가 된다
        return cameraRepository.existsActiveOrRunRequested(
                zoneId, List.of(CameraStatus.PREPARING, CameraStatus.RUNNING));
    }

    /** 그 구역의 캠 중 한 번이라도 가동한 것이 있는지. 삭제된 캠도 본다(구역 삭제 FNC-A-017 처리 순서 2). */
    @Transactional(readOnly = true)
    public boolean hasStartHistory(Long zoneId) {
        return cameraRepository.existsByZoneIdAndFirstStartedAtIsNotNull(zoneId);
    }

    /** 그 구역의 캠을 모두 실제로 지운다(이력 없는 구역 삭제). @return 지운 캠 수 */
    @Transactional
    public int deleteAllCameras(Long zoneId) {
        return cameraRepository.deleteAllByZoneId(zoneId);
    }

    /** 그 구역의 삭제되지 않은 캠에 삭제 표시를 한다(이력 있는 구역 삭제). @return 표시한 캠 수 */
    @Transactional
    public int markAllCamerasDeleted(Long zoneId, LocalDateTime now) {
        return cameraRepository.softDeleteAllByZoneId(zoneId, now);
    }
}
