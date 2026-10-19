/*
 * [해석본] 원본: admin/src/main/java/com/whiteeyes/admin/camera/service/CameraStatusService.java (feature/15-cam-status-report 브랜치)
 * 관련 이슈: #15(FNC-A-025 캠 상태 보고 수신)
 *
 * [무엇] 분석 서버의 상태 보고를 받아 camera의 status·progress·error_message를 바꾸는 업무 로직.
 * [이번에 구현한 것 / 하지 않은 것]
 *   - 처리 순서 1(상태 갱신)과 업무 규칙(엇갈림 정리)만 구현했다.
 *   - 처리 순서 2(알림 SSE 전송, FNC-A-048), 3·4(캠 이상 이벤트 열기·닫기, FNC-A-035)는 이슈에서 '범위 밖(2단계)'으로 정했다.
 *     나중에 붙일 자리는 updateStatus 바로 뒤다.
 * [염두에 둔 것]
 *   - 판단은 서비스가, 값 바꾸기는 엔티티가 한다. "RUNNING 보고를 무시할지"는 여기서, "progress가 null이면 유지"는 Camera.updateStatus에서.
 *   - 같은 도메인(camera)이라 CameraRepository를 직접 쓴다(package-structure 3-2 "같은 도메인이면 모듈이 달라도 repository 사용 가능").
 */
package com.whiteeyes.admin.camera.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClientException;

import com.whiteeyes.admin.camera.client.AnalyzerClient;
import com.whiteeyes.admin.camera.dto.CameraStatusUpdateRequest;
import com.whiteeyes.admin.camera.exception.CameraNotFoundException;
import com.whiteeyes.common.camera.entity.Camera;
import com.whiteeyes.common.camera.entity.CameraStatus;
import com.whiteeyes.common.camera.repository.CameraRepository;

@Service
public class CameraStatusService {

    private static final Logger log = LoggerFactory.getLogger(CameraStatusService.class);

    private final CameraRepository cameraRepository;
    private final AnalyzerClient analyzerClient;

    public CameraStatusService(CameraRepository cameraRepository, AnalyzerClient analyzerClient) {
        this.cameraRepository = cameraRepository;
        this.analyzerClient = analyzerClient;
    }

    // [해석] @Transactional: 메서드가 끝날 때 JPA가 바뀐 엔티티를 찾아 UPDATE를 날린다(변경 감지).
    //        그래서 camera.updateStatus(...)만 부르고 save를 따로 부르지 않아도 DB에 반영된다.
    //        예외가 나면 바꾼 내용이 모두 취소(롤백)된다.
    @Transactional
    public void reportStatus(Long cameraId, CameraStatusUpdateRequest request) {
        // [해석] 없는 캠 → 404(FNC-A-025 예외 처리). 삭제된 캠도 '없음'으로 본다(PR #93에 '검토 필요'로 적은 해석).
        //        orElseThrow: Optional이 비어 있으면 예외를 던진다. null 검사를 잊는 실수를 구조적으로 막는다.
        Camera camera = cameraRepository
                .findByIdAndDeletedAtIsNull(cameraId)
                .orElseThrow(() -> new CameraNotFoundException(cameraId));

        // 정지를 원하는 캠의 RUNNING 보고는 무시하고 다시 정지 지시(엇갈림 정리, FNC-A-025)
        // [해석] 어떻게 이런 엇갈림이 생기나: 관리자가 정지를 눌렀는데(run_requested=0) 분석 서버가 그 지시를 못 받았거나,
        //        분석 서버가 재시작하며 예전 작업을 이어 갈 때. 이때 RUNNING을 그대로 저장하면 화면에는 '가동 중'인데
        //        관리자는 정지를 원한 상태가 된다. 그래서 상태는 그대로 두고 분석 서버를 다시 멈춘다.
        //        RUNNING만 막는 이유: STOPPED·ERROR 보고는 관리자 의도(정지)와 어긋나지 않으므로 그대로 반영해도 된다.
        if (request.status() == CameraStatus.RUNNING && !camera.isRunRequested()) {
            stopMismatchedCamera(cameraId);
            return;
        }

        // [해석] 바꾸기 전 상태를 기억해 두는 이유: 아래 로그 레벨을 정하려고.
        CameraStatus previousStatus = camera.getStatus();
        camera.updateStatus(request.status(), request.progress(), request.errorMessage());
        // 준비 중 진행률 보고는 자주 오므로 상태가 바뀔 때만 INFO
        // [해석] code-conventions 7-3: INFO는 '운영에 의미 있는 1회성 상태 변화'(예: PREPARING → RUNNING),
        //        반복되는 처리는 DEBUG. 준비 중에는 45%, 46%…처럼 진행률만 계속 오므로 그건 DEBUG로 남긴다.
        //        로그 메시지는 {} 파라미터 방식(문자열 더하기 금지, 7-3).
        if (previousStatus != request.status()) {
            log.info("캠 상태 변경 cameraId={} {} -> {}", cameraId, previousStatus, request.status());
        } else {
            log.debug("캠 상태 보고 cameraId={} status={} progress={}", cameraId, request.status(), request.progress());
        }
    }

    // [해석] '정지 지시 + 실패 처리'를 따로 뺀 이유: reportStatus가 여러 책임을 지지 않게(code-conventions 2-5),
    //        그리고 try/catch가 본 흐름을 가리지 않게.
    private void stopMismatchedCamera(Long cameraId) {
        try {
            analyzerClient.stopCamera(cameraId);
            log.info("정지 요청된 캠의 RUNNING 보고 무시, 정지 지시 cameraId={}", cameraId);
        } catch (RestClientException e) {
            // 분석 서버는 다음 상태 보고 때 다시 정리되므로 보고 자체는 성공으로 처리
            // [해석] 사용자와 합의: 정지 지시가 실패해도 204 + WARN. 실패를 그대로 던지면 분석 서버는 '상태 보고 실패'로 받아
            //        보고를 다시 보내고, 그때 또 엇갈림 → 다시 정지 시도가 일어난다. 즉 다음 보고가 곧 재시도라서 여기서 실패를
            //        키울 이유가 없다. 빈 catch는 금지(2-6)라 WARN 로그를 남기고, 4xx/연결 실패는 예상된 일이라 스택 없이
            //        메시지만 남긴다(7-3 "예상된 업무 예외는 스택 없이 WARN", "분석 서버 연결 실패"는 WARN의 예).
            log.warn("엇갈린 캠 정지 지시 실패 cameraId={} reason={}", cameraId, e.getMessage());
        }
    }
}
