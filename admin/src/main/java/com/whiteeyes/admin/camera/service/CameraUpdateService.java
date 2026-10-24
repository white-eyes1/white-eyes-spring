package com.whiteeyes.admin.camera.service;

import com.whiteeyes.admin.camera.dto.CameraResponse;
import com.whiteeyes.admin.camera.dto.CameraUpdateRequest;
import com.whiteeyes.admin.camera.exception.CameraNotFoundException;
import com.whiteeyes.admin.camera.exception.CameraNotStoppedException;
import com.whiteeyes.admin.history.service.AdminWorkRecorder;
import com.whiteeyes.common.camera.entity.Camera;
import com.whiteeyes.common.camera.entity.CameraSourceType;
import com.whiteeyes.common.camera.entity.CameraStatus;
import com.whiteeyes.common.camera.repository.CameraRepository;
import com.whiteeyes.common.exception.BusinessException;
import com.whiteeyes.common.exception.ErrorCode;
import com.whiteeyes.common.history.entity.WorkAction;
import com.whiteeyes.common.history.entity.WorkTargetType;
import com.whiteeyes.common.security.LoginAccount;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 정지 상태인 캠의 이름·입력 방식·영상 주소를 고친다(FNC-A-019). */
@Service
@RequiredArgsConstructor
public class CameraUpdateService {

    private final CameraRepository cameraRepository;
    private final CameraSourceUriValidator sourceUriValidator;
    private final AdminWorkRecorder workRecorder;

    /**
     * @throws CameraNotFoundException 없거나 삭제된 캠이면(404)
     * @throws CameraNotStoppedException 정지 상태가 아니거나 가동 요청이 남아 있으면(409)
     */
    @Transactional
    public CameraResponse update(Long cameraId, CameraUpdateRequest request) {
        // 로그인하지 않은 요청은 캠을 바꾸기 전에 거부한다(작업 기록의 행동한 계정을 채울 수 없다)
        LoginAccount actor = workRecorder.requireActor();
        if (request.name() == null && request.sourceType() == null && request.sourceUri() == null) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "name, sourceType, sourceUri 중 하나는 보내야 합니다.");
        }
        // 행을 잠가서 읽는다: 상태 확인과 저장 사이에 가동 요청이 끼어드는 것을 막는다(캠 삭제와 같은 방식)
        Camera camera = cameraRepository.findWithLockByIdAndDeletedAtIsNull(cameraId)
                .orElseThrow(() -> new CameraNotFoundException(cameraId));
        // 처리 순서 1: 정지 상태(STOPPED)이고 가동 요청이 없을 때만 고칠 수 있다(가동 중·준비 중·오류는 먼저 정지)
        if (camera.getStatus() != CameraStatus.STOPPED || camera.isRunRequested()) {
            throw new CameraNotStoppedException(cameraId, camera.getStatus(), camera.isRunRequested());
        }
        // 처리 순서 2: 바뀐 뒤의 입력 방식과 영상 주소로 규칙 검사 후 저장
        String name = request.name() == null ? camera.getName() : request.name().trim();
        CameraSourceType sourceType = request.sourceType() == null ? camera.getSourceType() : request.sourceType();
        String sourceUri = request.sourceUri() == null ? camera.getSourceUri() : request.sourceUri().trim();
        sourceUriValidator.validate(sourceType, sourceUri);
        Map<String, Object> before = snapshot(camera);
        camera.updateInfo(name, sourceType, sourceUri);
        cameraRepository.saveAndFlush(camera);
        // 처리 순서 3: 작업 기록(이전·이후 값). 같은 트랜잭션이라 기록이 실패하면 수정도 취소된다
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("op", "UPDATE");
        detail.put("zoneId", camera.getZoneId());
        detail.put("before", before);
        detail.put("after", snapshot(camera));
        workRecorder.record(actor, WorkAction.CAMERA_CHANGE, WorkTargetType.CAMERA, camera.getId(), camera.getName(), detail);
        return CameraResponse.from(camera);
    }

    private Map<String, Object> snapshot(Camera camera) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("name", camera.getName());
        values.put("sourceType", camera.getSourceType());
        values.put("sourceUri", camera.getSourceUri());
        return values;
    }
}
