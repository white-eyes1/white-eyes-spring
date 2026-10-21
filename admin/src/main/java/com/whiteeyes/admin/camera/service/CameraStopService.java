package com.whiteeyes.admin.camera.service;

import com.whiteeyes.admin.camera.client.AnalyzerClient;
import com.whiteeyes.admin.camera.dto.CameraStopResponse;
import com.whiteeyes.admin.camera.exception.CameraNotFoundException;
import com.whiteeyes.common.camera.entity.CameraStatus;
import com.whiteeyes.common.camera.repository.CameraRepository;
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

    private final CameraRepository cameraRepository;
    private final AnalyzerClient analyzerClient;
    private final TransactionTemplate transactionTemplate;

    public CameraStopResponse stop(Long cameraId) {
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
        transactionTemplate.executeWithoutResult(status -> markStopped(cameraId));
        // 처리 순서 4(더미 캠: 마지막 녹화의 ended_at 채우기)는 녹화 등록(FNC-A-026, #17)의 recording 테이블이,
        // 처리 순서 5(열린 캠 이상 이벤트 종료)는 이벤트 테이블이, 처리 순서 6(작업 기록 CAMERA_RUN, op: STOP)은
        // 작업 기록 저장(FNC-C-026, #6)이 dev에 들어오면 여기서 호출한다
        return new CameraStopResponse(CameraStatus.STOPPED, warning);
    }

    private void cancelRunRequest(Long cameraId) {
        cameraRepository.findByIdAndDeletedAtIsNull(cameraId)
                .orElseThrow(() -> new CameraNotFoundException(cameraId))
                .cancelRunRequest();
    }

    private void markStopped(Long cameraId) {
        cameraRepository.findByIdAndDeletedAtIsNull(cameraId)
                .ifPresent(camera -> camera.updateStatus(CameraStatus.STOPPED, STOPPED_PROGRESS, null));
    }
}
