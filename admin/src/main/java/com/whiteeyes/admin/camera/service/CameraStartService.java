package com.whiteeyes.admin.camera.service;

import com.whiteeyes.admin.camera.client.AnalyzerClient;
import com.whiteeyes.admin.camera.dto.CameraStartResponse;
import com.whiteeyes.admin.camera.exception.AnalyzerUnavailableException;
import com.whiteeyes.admin.camera.exception.CameraNotFoundException;
import com.whiteeyes.admin.camera.exception.InvalidCameraStatusException;
import com.whiteeyes.admin.camera.exception.SourceNotFoundException;
import com.whiteeyes.common.camera.entity.Camera;
import com.whiteeyes.common.camera.entity.CameraSourceType;
import com.whiteeyes.common.camera.entity.CameraStatus;
import com.whiteeyes.common.camera.repository.CameraRepository;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 캠 가동을 분석 서버에 지시한다(FNC-A-020).
 *
 * <p>분석 서버 호출(최대 5초)을 DB 트랜잭션 안에 넣지 않으려고 ① 가동 요청 기록 ② 분석 서버 호출 ③ 결과 기록의
 * 세 단계를 따로 커밋한다. 실패해도 ERROR 상태와 run_requested=1을 남겨야 하므로(예외를 던지면 롤백된다)
 * 메서드 전체를 {@code @Transactional}로 묶지 않는다.
 */
@Service
@RequiredArgsConstructor
public class CameraStartService {

    private static final String UNAVAILABLE_MESSAGE = "분석 서버 연결 실패";
    private static final String SOURCE_NOT_FOUND_MESSAGE = "영상 파일·스트림 없음";
    private static final int PREPARING_START_PROGRESS = 0;

    private final CameraRepository cameraRepository;
    private final AnalyzerClient analyzerClient;
    private final TransactionTemplate transactionTemplate;

    public CameraStartResponse start(Long cameraId) {
        // 처리 순서 1·2: STOPPED·ERROR인지 확인하고 run_requested=1 기록
        StartTarget target = transactionTemplate.execute(status -> requestRun(cameraId));

        // 처리 순서 3: 분석 서버 호출. 실패해도 run_requested=1은 유지한다(분석 서버가 다시 켜지면 자동 가동)
        CameraStatus started;
        try {
            started = analyzerClient.startCamera(cameraId, target.sourceType(), target.sourceUri());
        } catch (AnalyzerUnavailableException e) {
            recordFailure(cameraId, UNAVAILABLE_MESSAGE);
            throw e;
        } catch (SourceNotFoundException e) {
            recordFailure(cameraId, SOURCE_NOT_FOUND_MESSAGE);
            throw e;
        }

        // 처리 순서 4: 응답의 상태로 갱신, first_started_at이 비어 있으면 현재 시각
        recordStarted(cameraId, started);
        // 처리 순서 5(작업 기록 CAMERA_RUN, detail: {op: START})는 작업 기록 저장(FNC-C-026, #6)이 들어오면 여기서 호출한다
        return new CameraStartResponse(started);
    }

    private StartTarget requestRun(Long cameraId) {
        Camera camera = cameraRepository.findByIdAndDeletedAtIsNull(cameraId)
                .orElseThrow(() -> new CameraNotFoundException(cameraId));
        if (!isStartable(camera.getStatus())) {
            throw new InvalidCameraStatusException(cameraId, camera.getStatus());
        }
        camera.requestRun();
        return new StartTarget(camera.getSourceType(), camera.getSourceUri());
    }

    private void recordFailure(Long cameraId, String errorMessage) {
        transactionTemplate.executeWithoutResult(status ->
                cameraRepository.findByIdAndDeletedAtIsNull(cameraId)
                        .ifPresent(camera -> camera.updateStatus(CameraStatus.ERROR, null, errorMessage)));
    }

    private void recordStarted(Long cameraId, CameraStatus started) {
        transactionTemplate.executeWithoutResult(status ->
                cameraRepository.findByIdAndDeletedAtIsNull(cameraId).ifPresent(camera -> {
                    // 분석 서버의 상태 보고(FNC-A-025)가 응답보다 먼저 와서 이미 상태가 바뀌었다면 덮어쓰지 않는다
                    if (isStartable(camera.getStatus())) {
                        Integer progress = started == CameraStatus.PREPARING ? PREPARING_START_PROGRESS : null;
                        camera.updateStatus(started, progress, null);
                    }
                    camera.markFirstStartedIfAbsent(LocalDateTime.now());
                }));
    }

    private boolean isStartable(CameraStatus status) {
        return status == CameraStatus.STOPPED || status == CameraStatus.ERROR;
    }

    private record StartTarget(CameraSourceType sourceType, String sourceUri) {}
}
