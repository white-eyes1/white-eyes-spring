package com.whiteeyes.admin.camera.service;

import com.whiteeyes.admin.camera.client.AnalyzerClient;
import com.whiteeyes.admin.camera.dto.CameraStopResponse;
import com.whiteeyes.admin.camera.exception.CameraNotFoundException;
import com.whiteeyes.admin.event.service.CamFaultEventService;
import com.whiteeyes.admin.history.service.AdminWorkRecorder;
import com.whiteeyes.admin.media.repository.RecordingRepository;
import com.whiteeyes.common.camera.entity.CameraSourceType;
import com.whiteeyes.common.camera.entity.CameraStatus;
import com.whiteeyes.common.camera.repository.CameraRepository;
import com.whiteeyes.common.history.entity.WorkAction;
import com.whiteeyes.common.history.entity.WorkTargetType;
import com.whiteeyes.common.security.LoginAccount;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClientException;

/**
 * 캠 정지를 분석 서버에 지시한다(FNC-A-021).
 *
 * <p>분석 서버 호출(최대 15초)을 DB 트랜잭션 안에 넣지 않으려고 ① 가동 요청 취소 ② 분석 서버 호출 ③ 정지 상태 기록의
 * 세 단계를 따로 커밋한다. 분석 서버에 전달하지 못해도 앱 쪽은 정지로 저장해야 하므로 호출 실패를 예외로 던지지 않는다.
 */
@Service
@RequiredArgsConstructor
@Log4j2
public class CameraStopService {

    private static final String DELIVERY_FAILED_WARNING = "분석 서버에 전달 실패";
    private static final int STOPPED_PROGRESS = 0;
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final CameraRepository cameraRepository;
    private final AnalyzerClient analyzerClient;
    private final TransactionTemplate transactionTemplate;
    private final RecordingRepository recordingRepository;
    private final AdminWorkRecorder workRecorder;
    private final CamFaultEventService camFaultEventService;

    public CameraStopResponse stop(Long cameraId) {
        // 로그인하지 않은 요청은 정지를 시작하기 전에 거부한다(작업 기록의 행동한 계정을 채울 수 없다)
        LoginAccount actor = workRecorder.requireActor();
        // 처리 순서 1: run_requested=0. 분석 서버가 다시 켜져도 가동하지 않게 호출보다 먼저 확정한다
        transactionTemplate.executeWithoutResult(status -> cancelRunRequest(cameraId));

        // 처리 순서 2: 분석 서버에 정지 지시(타임아웃 15초). 준비 중이면 분석 서버가 준비를 취소한다
        String warning = null;
        try {
            analyzerClient.stopCamera(cameraId);
        } catch (RestClientException e) {
            // 연결 실패·타임아웃·오류 응답 모두 '전달 실패'. 앱 쪽 정지는 그대로 진행한다
            log.warn("분석 서버에 캠 정지를 전달하지 못했습니다 cameraId={}: {}", cameraId, e.getMessage());
            warning = DELIVERY_FAILED_WARNING;
        }

        // 처리 순서 3: status=STOPPED, progress=0 (이전 오류 내용도 지운다)
        transactionTemplate.executeWithoutResult(status -> markStopped(cameraId, actor));
        // 처리 순서 4(더미 캠의 마지막 녹화 닫기), 5(열린 캠 이상 이벤트 종료, FNC-A-035), 6(작업 기록 CAMERA_RUN, op: STOP)은
        // 위 3단계 트랜잭션 안에서 한다
        return new CameraStopResponse(CameraStatus.STOPPED, warning);
    }

    private void cancelRunRequest(Long cameraId) {
        cameraRepository.findByIdAndDeletedAtIsNull(cameraId)
                .orElseThrow(() -> new CameraNotFoundException(cameraId))
                .cancelRunRequest();
    }

    private void markStopped(Long cameraId, LoginAccount actor) {
        cameraRepository.findByIdAndDeletedAtIsNull(cameraId).ifPresent(camera -> {
            camera.updateStatus(CameraStatus.STOPPED, STOPPED_PROGRESS, null);
            // 정지한 캠은 감시 대상이 아니므로 열린 캠 이상 이벤트를 같은 트랜잭션에서 닫는다(FNC-A-035)
            camFaultEventService.closeOnStop(cameraId, LocalDateTime.now(KST));
            // 처리 순서 4: 더미 캠은 종료 시각이 빈 마지막 녹화를 지금 시각으로 닫는다(라이브 캠은 분석 서버가 파일을 닫아 등록한다)
            if (camera.getSourceType() == CameraSourceType.DUMMY) {
                recordingRepository
                        .findFirstByCameraIdAndEndedAtIsNullOrderByStartedAtDesc(cameraId)
                        .ifPresent(recording -> recording.close(LocalDateTime.now(KST)));
            }
            workRecorder.record(
                    actor, WorkAction.CAMERA_RUN, WorkTargetType.CAMERA, cameraId, camera.getName(), Map.of("op", "STOP"));
        });
    }
}
