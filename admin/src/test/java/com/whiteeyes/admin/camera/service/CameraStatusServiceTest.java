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

    @InjectMocks
    private CameraStatusService cameraStatusService;

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

    @Test
    @DisplayName("FNC-A-025 progress가 없으면 기존 진행률 유지")
    void keepsProgressWhenNotReported() {
        Camera camera = givenCamera(true, CameraStatus.PREPARING, 45, null);

        cameraStatusService.reportStatus(CAMERA_ID, new CameraStatusUpdateRequest(CameraStatus.PREPARING, null, null));

        assertThat(camera.getProgress()).isEqualTo(45);
    }

    @Test
    @DisplayName("FNC-A-025 errorMessage가 null이면 오류 내용을 지움")
    void clearsErrorMessageWhenNull() {
        Camera camera = givenCamera(true, CameraStatus.ERROR, 0, "분석 서버 연결 실패");

        cameraStatusService.reportStatus(CAMERA_ID, new CameraStatusUpdateRequest(CameraStatus.RUNNING, 100, null));

        assertThat(camera.getStatus()).isEqualTo(CameraStatus.RUNNING);
        assertThat(camera.getErrorMessage()).isNull();
    }

    @Test
    @DisplayName("FNC-A-025 run_requested=0인 캠의 RUNNING 보고는 무시하고 분석 서버에 정지 지시")
    void ignoresRunningReportAndStopsWhenRunNotRequested() {
        Camera camera = givenCamera(false, CameraStatus.STOPPED, 0, null);

        cameraStatusService.reportStatus(CAMERA_ID, new CameraStatusUpdateRequest(CameraStatus.RUNNING, 100, null));

        assertThat(camera.getStatus()).isEqualTo(CameraStatus.STOPPED);
        assertThat(camera.getProgress()).isZero();
        verify(analyzerClient).stopCamera(CAMERA_ID);
    }

    @Test
    @DisplayName("FNC-A-025 엇갈림 정리의 정지 지시가 실패해도 보고 처리는 성공")
    void succeedsEvenWhenStopCallFails() {
        givenCamera(false, CameraStatus.STOPPED, 0, null);
        willThrow(new ResourceAccessException("연결 실패")).given(analyzerClient).stopCamera(CAMERA_ID);

        assertThatNoException()
                .isThrownBy(() -> cameraStatusService.reportStatus(
                        CAMERA_ID, new CameraStatusUpdateRequest(CameraStatus.RUNNING, 100, null)));
    }

    @Test
    @DisplayName("FNC-A-025 run_requested=0이어도 RUNNING이 아닌 보고는 반영")
    void appliesNonRunningReportWhenRunNotRequested() {
        Camera camera = givenCamera(false, CameraStatus.PREPARING, 30, null);

        cameraStatusService.reportStatus(CAMERA_ID, new CameraStatusUpdateRequest(CameraStatus.STOPPED, 0, null));

        assertThat(camera.getStatus()).isEqualTo(CameraStatus.STOPPED);
        assertThat(camera.getProgress()).isZero();
        verify(analyzerClient, never()).stopCamera(CAMERA_ID);
    }

    @Test
    @DisplayName("FNC-A-025 없는 캠이면 CameraNotFoundException")
    void throwsWhenCameraNotFound() {
        given(cameraRepository.findByIdAndDeletedAtIsNull(CAMERA_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> cameraStatusService.reportStatus(
                        CAMERA_ID, new CameraStatusUpdateRequest(CameraStatus.RUNNING, 100, null)))
                .isInstanceOf(CameraNotFoundException.class);
    }

    // TODO(#11): Camera에 생성 메서드가 생기면 그것으로 바꾼다
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
