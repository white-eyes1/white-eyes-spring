package com.whiteeyes.admin.camera.service;

import com.whiteeyes.admin.camera.dto.CameraDeleteResponse;
import com.whiteeyes.admin.camera.exception.CameraNotFoundException;
import com.whiteeyes.admin.camera.exception.CameraNotStoppedException;
import com.whiteeyes.admin.history.service.AdminWorkRecorder;
import com.whiteeyes.common.camera.entity.Camera;
import com.whiteeyes.common.camera.entity.CameraStatus;
import com.whiteeyes.common.camera.repository.CameraRepository;
import com.whiteeyes.common.history.entity.WorkAction;
import com.whiteeyes.common.history.entity.WorkTargetType;
import com.whiteeyes.common.security.LoginAccount;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 캠을 삭제한다(FNC-A-023). 가동한 적이 없으면 행을 지우고, 있으면 삭제 표시만 한다. */
@Service
@RequiredArgsConstructor
public class CameraDeleteService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final CameraRepository cameraRepository;
    private final AdminWorkRecorder workRecorder;

    @Transactional
    public CameraDeleteResponse delete(Long cameraId) {
        // 로그인하지 않은 요청은 삭제하기 전에 거부한다(작업 기록의 행동한 계정을 채울 수 없다)
        LoginAccount actor = workRecorder.requireActor();
        // 행을 잠가 읽는다: 가동이 막 상태를 바꾸는 중이면 그 결과를 보고 판단한다(검사와 삭제 사이에 끼어들지 못하게)
        Camera camera = cameraRepository
                .findWithLockByIdAndDeletedAtIsNull(cameraId)
                .orElseThrow(() -> new CameraNotFoundException(cameraId));
        // 처리 순서 1: status=STOPPED 확인. 가동 요청 상태(run_requested=1)도 거부한다:
        // 가동은 요청 기록 -> 분석 서버 호출 -> 결과 기록으로 나뉘어, 그 사이에 지우면 분석 서버만 캠을 돌리게 된다
        if (camera.getStatus() != CameraStatus.STOPPED || camera.isRunRequested()) {
            throw new CameraNotStoppedException(cameraId, camera.getStatus(), camera.isRunRequested());
        }
        // 처리 순서 2: 가동한 적이 없으면 실제 삭제, 있으면 deleted_at 설정
        CameraDeleteResponse.Mode mode;
        if (camera.getFirstStartedAt() == null) {
            cameraRepository.delete(camera);
            mode = CameraDeleteResponse.Mode.HARD;
        } else {
            camera.markDeleted(LocalDateTime.now(KST));
            mode = CameraDeleteResponse.Mode.SOFT;
        }
        // 처리 순서 3: 작업 기록(CAMERA_CHANGE). 같은 트랜잭션이라 기록이 실패하면 삭제도 취소된다
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("op", "DELETE");
        detail.put("zoneId", camera.getZoneId());
        detail.put("mode", mode);
        workRecorder.record(actor, WorkAction.CAMERA_CHANGE, WorkTargetType.CAMERA, cameraId, camera.getName(), detail);
        return new CameraDeleteResponse(mode);
    }
}
