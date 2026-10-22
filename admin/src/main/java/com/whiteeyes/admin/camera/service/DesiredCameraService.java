package com.whiteeyes.admin.camera.service;

import com.whiteeyes.admin.camera.dto.DesiredCameraResponse;
import com.whiteeyes.common.camera.repository.CameraRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 가동 대상 캠 목록 제공(FNC-A-027) */
@Service
@RequiredArgsConstructor
public class DesiredCameraService {

    private final CameraRepository cameraRepository;

    /**
     * run_requested=1이고 삭제되지 않은 캠을 번호 순으로 돌려준다. 오류 상태 캠도 포함하고,
     * 관리자가 정지한 캠(run_requested=0)은 뺀다.
     */
    @Transactional(readOnly = true)
    public List<DesiredCameraResponse> findDesiredCameras() {
        return cameraRepository.findByRunRequestedTrueAndDeletedAtIsNullOrderByIdAsc().stream()
                .map(DesiredCameraResponse::from)
                .toList();
    }
}
