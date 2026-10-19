/*
 * [해석본] 원본: admin/src/test/java/com/whiteeyes/admin/camera/service/CameraStatusServiceTest.java (feature/15-cam-status-report 브랜치)
 * 관련 이슈: #15(FNC-A-025)
 *
 * [무엇] 상태 보고 서비스의 업무 규칙을 하나씩 확인한다. 명세의 검수 기준이 "-"(비어 있음)라서, 이슈의 완료 기준
 *   "명세의 처리 순서·예외 처리대로 동작"을 처리 순서·업무 규칙·예외 하나당 테스트 하나로 바꿨다(CLAUDE.md "검수 기준은 테스트로").
 *   + 사용자와 합의한 기본값 4가지(progress 유지, errorMessage 지움, stop 실패해도 성공 등)도 각각 테스트로 고정했다.
 *     나중에 누가 합의를 모르고 동작을 바꾸면 이 테스트가 깨져 알려 준다.
 * [가짜(mock)] CameraRepository(DB)와 AnalyzerClient(분석 서버)는 가짜. 진짜 DB·서버 없이 '조건 → 결과'만 빠르게 본다.
 *   Camera는 진짜 객체라 updateStatus 후 값이 실제로 바뀌었는지 getter로 확인할 수 있다.
 */
package com.whiteeyes.admin.camera.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.BeanUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.ResourceAccessException;

import com.whiteeyes.admin.camera.client.AnalyzerClient;
import com.whiteeyes.admin.camera.dto.CameraStatusUpdateRequest;
import com.whiteeyes.admin.camera.exception.CameraNotFoundException;
import com.whiteeyes.common.camera.entity.Camera;
import com.whiteeyes.common.camera.entity.CameraStatus;
import com.whiteeyes.common.camera.repository.CameraRepository;

@ExtendWith(MockitoExtension.class)
class CameraStatusServiceTest {

    private static final Long CAMERA_ID = 7L;

    @Mock
    private CameraRepository cameraRepository;

    @Mock
    private AnalyzerClient analyzerClient;

    // [해석] 위 두 가짜를 생성자로 넣어 진짜 CameraStatusService를 만든다.
    @InjectMocks
    private CameraStatusService cameraStatusService;

    // [해석] 처리 순서 1. 준비 중(45%)이던 캠이 ERROR(0%, 오류 내용 있음)를 보고 → 세 값이 모두 바뀌었는가.
    //        오류 문구는 FNC-A-020에 나오는 실제 문구('영상 파일·스트림 없음')를 썼다.
    @Test
    @DisplayName("FNC-A-025 보고받은 상태·진행률·오류 내용으로 갱신")
    void updatesStatusProgressAndErrorMessage() {
        Camera camera = givenCamera(true, CameraStatus.PREPARING, 45, null);

        cameraStatusService.reportStatus(
                CAMERA_ID, new CameraStatusUpdateRequest(CameraStatus.ERROR, 0, "영상 파일·스트림 없음"));

        assertThat(camera.getStatus()).isEqualTo(CameraStatus.ERROR);
        assertThat(camera.getProgress()).isZero();
        assertThat(camera.getErrorMessage()).isEqualTo("영상 파일·스트림 없음");
    }

    // [해석] 합의 1: progress를 안 보내면(null) 기존 45%를 유지. 0으로 바뀌면 화면의 진행률 막대가 갑자기 0으로 튄다.
    @Test
    @DisplayName("FNC-A-025 progress가 없으면 기존 진행률 유지")
    void keepsProgressWhenNotReported() {
        Camera camera = givenCamera(true, CameraStatus.PREPARING, 45, null);

        cameraStatusService.reportStatus(CAMERA_ID, new CameraStatusUpdateRequest(CameraStatus.PREPARING, null, null));

        assertThat(camera.getProgress()).isEqualTo(45);
    }

    // [해석] 합의 2: 오류에서 복구(RUNNING, errorMessage=null)하면 옛 오류 내용이 지워진다.
    //        남아 있으면 정상 가동 중인 캠 옆에 '분석 서버 연결 실패'가 계속 보이게 된다.
    @Test
    @DisplayName("FNC-A-025 errorMessage가 null이면 오류 내용을 지움")
    void clearsErrorMessageWhenNull() {
        Camera camera = givenCamera(true, CameraStatus.ERROR, 0, "분석 서버 연결 실패");

        cameraStatusService.reportStatus(CAMERA_ID, new CameraStatusUpdateRequest(CameraStatus.RUNNING, 100, null));

        assertThat(camera.getStatus()).isEqualTo(CameraStatus.RUNNING);
        assertThat(camera.getErrorMessage()).isNull();
    }

    // [해석] 업무 규칙(엇갈림 정리). 관리자는 껐는데(run_requested=0) RUNNING 보고가 옴 →
    //        ① 상태는 STOPPED 그대로, 진행률도 그대로(=아무것도 저장 안 함) ② 분석 서버에 정지 지시를 보냈다.
    //        ①만 보면 '그냥 무시'와 구분이 안 되고, ②만 보면 상태를 잘못 저장해도 통과하므로 둘 다 본다.
    @Test
    @DisplayName("FNC-A-025 run_requested=0인 캠의 RUNNING 보고는 무시하고 분석 서버에 정지 지시")
    void ignoresRunningReportAndStopsWhenRunNotRequested() {
        Camera camera = givenCamera(false, CameraStatus.STOPPED, 0, null);

        cameraStatusService.reportStatus(CAMERA_ID, new CameraStatusUpdateRequest(CameraStatus.RUNNING, 100, null));

        assertThat(camera.getStatus()).isEqualTo(CameraStatus.STOPPED);
        assertThat(camera.getProgress()).isZero();
        verify(analyzerClient).stopCamera(CAMERA_ID);
    }

    // [해석] 합의 4: 정지 지시가 연결 실패(ResourceAccessException = RestClientException의 한 종류)여도 예외가 밖으로 안 나간다
    //        → 컨트롤러는 204를 돌려준다. willThrow: 가짜 클라이언트가 호출되면 예외를 던지게 설정.
    @Test
    @DisplayName("FNC-A-025 엇갈림 정리의 정지 지시가 실패해도 보고 처리는 성공")
    void succeedsEvenWhenStopCallFails() {
        givenCamera(false, CameraStatus.STOPPED, 0, null);
        willThrow(new ResourceAccessException("연결 실패")).given(analyzerClient).stopCamera(CAMERA_ID);

        assertThatNoException()
                .isThrownBy(() -> cameraStatusService.reportStatus(
                        CAMERA_ID, new CameraStatusUpdateRequest(CameraStatus.RUNNING, 100, null)));
    }

    // [해석] 엇갈림 규칙이 너무 넓게 걸리지 않는지. run_requested=0이어도 STOPPED 보고(정지 완료)는 그대로 반영하고
    //        정지 지시는 보내지 않아야 한다(never()). 이게 깨지면 정지한 캠의 상태가 영영 PREPARING에 머문다.
    @Test
    @DisplayName("FNC-A-025 run_requested=0이어도 RUNNING이 아닌 보고는 반영")
    void appliesNonRunningReportWhenRunNotRequested() {
        Camera camera = givenCamera(false, CameraStatus.PREPARING, 30, null);

        cameraStatusService.reportStatus(CAMERA_ID, new CameraStatusUpdateRequest(CameraStatus.STOPPED, 0, null));

        assertThat(camera.getStatus()).isEqualTo(CameraStatus.STOPPED);
        assertThat(camera.getProgress()).isZero();
        verify(analyzerClient, never()).stopCamera(CAMERA_ID);
    }

    // [해석] 예외 처리 "없는 캠: 404"의 서비스 쪽 절반. 서비스는 예외를 던지고, 404로 바꾸는 건 컨트롤러 테스트에서 본다.
    @Test
    @DisplayName("FNC-A-025 없는 캠이면 CameraNotFoundException")
    void throwsWhenCameraNotFound() {
        given(cameraRepository.findByIdAndDeletedAtIsNull(CAMERA_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> cameraStatusService.reportStatus(
                        CAMERA_ID, new CameraStatusUpdateRequest(CameraStatus.RUNNING, 100, null)))
                .isInstanceOf(CameraNotFoundException.class);
    }

    // TODO(#11): Camera에 생성 메서드가 생기면 그것으로 바꾼다
    // [해석] 테스트용 캠을 만들고, 가짜 리포지토리가 그 캠을 돌려주도록 설정까지 한 번에 한다(테스트마다 반복되는 준비).
    //        Camera에 setter·공개 생성자가 없어(엔티티 규칙) 리플렉션으로 값을 넣는다. #11에서 생성 메서드가 생기면 바꾼다.
    //        zoneId는 이 테스트들이 쓰지 않아 넣지 않았다(#16에서 추가된 칼럼).
    private Camera givenCamera(boolean runRequested, CameraStatus status, int progress, String errorMessage) {
        Camera camera = BeanUtils.instantiateClass(Camera.class);
        ReflectionTestUtils.setField(camera, "id", CAMERA_ID);
        ReflectionTestUtils.setField(camera, "runRequested", runRequested);
        ReflectionTestUtils.setField(camera, "status", status);
        ReflectionTestUtils.setField(camera, "progress", progress);
        ReflectionTestUtils.setField(camera, "errorMessage", errorMessage);
        given(cameraRepository.findByIdAndDeletedAtIsNull(CAMERA_ID)).willReturn(Optional.of(camera));
        return camera;
    }
}
