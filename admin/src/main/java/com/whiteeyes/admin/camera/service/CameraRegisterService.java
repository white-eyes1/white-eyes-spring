package com.whiteeyes.admin.camera.service;

import com.whiteeyes.admin.camera.dto.CameraCreateRequest;
import com.whiteeyes.admin.camera.dto.CameraResponse;
import com.whiteeyes.admin.camera.exception.ZoneHasCameraException;
import com.whiteeyes.admin.history.service.AdminWorkRecorder;
import com.whiteeyes.admin.region.service.ZoneService;
import com.whiteeyes.common.camera.entity.Camera;
import com.whiteeyes.common.camera.repository.CameraRepository;
import com.whiteeyes.common.history.entity.WorkAction;
import com.whiteeyes.common.history.entity.WorkTargetType;
import com.whiteeyes.common.security.LoginAccount;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 구역에 캠을 등록한다(FNC-A-018). 등록한 캠은 정지 상태다. */
@Service
@RequiredArgsConstructor
public class CameraRegisterService {

    private final ZoneService zoneService;
    private final CameraRepository cameraRepository;
    private final CameraSourceUriValidator sourceUriValidator;
    private final AdminWorkRecorder workRecorder;

    @Transactional
    public CameraResponse register(CameraCreateRequest request) {
        // 로그인하지 않은 요청은 캠을 만들기 전에 거부한다(작업 기록의 행동한 계정을 채울 수 없다)
        LoginAccount actor = workRecorder.requireActor();
        // 처리 순서 1: 구역 존재·삭제 여부 확인
        zoneService.requireActiveZone(request.zoneId());
        // 처리 순서 2: 그 구역에 삭제되지 않은 캠이 있으면 거부
        if (cameraRepository.existsByZoneIdAndDeletedAtIsNull(request.zoneId())) {
            throw new ZoneHasCameraException(request.zoneId());
        }
        // 처리 순서 3: 영상 주소 규칙 검사
        String sourceUri = request.sourceUri().trim();
        sourceUriValidator.validate(request.sourceType(), sourceUri);
        // 처리 순서 4: 저장(status=STOPPED, run_requested=0은 Camera 생성자가 정한다)
        Camera camera = cameraRepository.save(
                new Camera(request.zoneId(), request.name().trim(), request.sourceType(), sourceUri));
        // 처리 순서 5: 작업 기록(CAMERA_CHANGE). 같은 트랜잭션이라 기록이 실패하면 캠 등록도 취소된다
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("op", "CREATE");
        detail.put("zoneId", camera.getZoneId());
        detail.put("sourceType", camera.getSourceType());
        workRecorder.record(actor, WorkAction.CAMERA_CHANGE, WorkTargetType.CAMERA, camera.getId(), camera.getName(), detail);
        return CameraResponse.from(camera);
    }
}
