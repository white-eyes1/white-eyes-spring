package com.whiteeyes.admin.camera.service;

import com.whiteeyes.admin.camera.client.AnalyzerClient;
import com.whiteeyes.admin.camera.dto.CameraStatusUpdateRequest;
import com.whiteeyes.admin.camera.exception.CameraNotFoundException;
import com.whiteeyes.admin.event.service.CamFaultEventService;
import com.whiteeyes.common.camera.entity.Camera;
import com.whiteeyes.common.camera.entity.CameraStatus;
import com.whiteeyes.common.camera.repository.CameraRepository;
import java.time.LocalDateTime;
import java.time.ZoneId;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClientException;

/**
 * 분석 서버가 보낸 캠 상태 보고를 받는다(FNC-A-025).
 *
 * <p>정지 지시를 되돌려 보내는 분석 서버 호출(최대 15초)을 DB 트랜잭션 안에 넣지 않으려고 메서드 전체를
 * {@code @Transactional}로 묶지 않는다. 상태 갱신만 {@link TransactionTemplate}으로 감싼다.
 * 캠 이상 이벤트 열기·닫기(처리 순서 3·4, FNC-A-035)는 상태 갱신 트랜잭션이 끝난 뒤에 따로 하고, 실패하면
 * 로그만 남긴다(이벤트 저장 실패로 상태 갱신이 롤백되지 않게). 알림 SSE(처리 순서 2)는 하지 않는다.
 */
@Service
@RequiredArgsConstructor
@Log4j2
public class CameraStatusService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final CameraRepository cameraRepository;
    private final AnalyzerClient analyzerClient;
    private final TransactionTemplate transactionTemplate;
    private final CamFaultEventService camFaultEventService;

    public void reportStatus(Long cameraId, CameraStatusUpdateRequest request) {
        // 정지를 원하는 캠(run_requested=0)의 RUNNING 보고는 반영하지 않고 분석 서버에 정지를 다시 지시한다(엇갈림 정리)
        AppliedReport applied = transactionTemplate.execute(status -> apply(cameraId, request));
        if (applied.mismatched()) {
            stopMismatchedCamera(cameraId);
            return;
        }
        // 이벤트 처리는 상태 갱신이 커밋된 뒤에 한다. 실패해도 로그만 남기고 이미 저장한 상태는 유지한다
        handleCamFaultEvent(cameraId, request.status(), applied.previous());
    }

    /** 상태 갱신 트랜잭션의 결과. mismatched=true면 반영하지 않고 정지 지시로 넘겨야 하는 보고, previous는 갱신 전 상태. */
    private record AppliedReport(boolean mismatched, CameraStatus previous) {}

    /** 보고를 반영한다. */
    private AppliedReport apply(Long cameraId, CameraStatusUpdateRequest request) {
        Camera camera = cameraRepository.findByIdAndDeletedAtIsNull(cameraId)
                .orElseThrow(() -> new CameraNotFoundException(cameraId));
        if (request.status() == CameraStatus.RUNNING && !camera.isRunRequested()) {
            return new AppliedReport(true, camera.getStatus());
        }
        CameraStatus previous = camera.getStatus();
        camera.updateStatus(request.status(), request.progress(), request.errorMessage());
        // 준비 중 진행률 보고는 자주 오므로 상태가 바뀔 때만 INFO
        if (previous != request.status()) {
            log.info("캠 상태 변경 cameraId={} {} -> {}", cameraId, previous, request.status());
        } else {
            log.debug("캠 상태 보고 cameraId={} status={} progress={}", cameraId, request.status(), request.progress());
        }
        return new AppliedReport(false, previous);
    }

    // run_requested=0인 RUNNING 보고는 걸러졌으므로 여기 온 RUNNING은 가동 요청된 캠이다
    private void handleCamFaultEvent(Long cameraId, CameraStatus reported, CameraStatus previous) {
        try {
            if (reported == CameraStatus.ERROR) {
                camFaultEventService.openOnError(cameraId, LocalDateTime.now(KST));
            } else if (reported == CameraStatus.RUNNING) {
                camFaultEventService.closeOnRecovered(cameraId, LocalDateTime.now(KST), previous != CameraStatus.RUNNING);
            }
        } catch (RuntimeException e) {
            // 상태 보고는 성공으로 끝낸다. 이벤트는 10초마다 도는 캠 이상 검사가 다시 맞춘다
            log.error("캠 이상 이벤트 처리에 실패했습니다 cameraId={} status={}", cameraId, reported, e);
        }
    }

    private void stopMismatchedCamera(Long cameraId) {
        try {
            analyzerClient.stopCamera(cameraId);
            log.info("정지 요청된 캠의 RUNNING 보고를 무시하고 정지를 지시했습니다 cameraId={}", cameraId);
        } catch (RestClientException e) {
            // 분석 서버는 다음 상태 보고나 재시작 때 다시 정리되므로 보고 자체는 성공으로 처리한다
            log.warn("엇갈린 캠 정지 지시 실패 cameraId={}: {}", cameraId, e.getMessage());
        }
    }
}
