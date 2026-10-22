package com.whiteeyes.admin.camera.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

import com.whiteeyes.admin.camera.dto.CameraStatusUpdateRequest;
import com.whiteeyes.admin.camera.dto.DesiredCameraResponse;
import com.whiteeyes.admin.camera.exception.CameraNotFoundException;
import com.whiteeyes.admin.camera.service.CameraStatusService;
import com.whiteeyes.admin.camera.service.DesiredCameraService;
import com.whiteeyes.common.camera.entity.CameraSourceType;
import com.whiteeyes.common.camera.entity.CameraStatus;
import com.whiteeyes.common.exception.GlobalExceptionHandler;

/** 캠 상태 보고 수신(FNC-A-025) */
@ExtendWith(MockitoExtension.class)
class CameraInternalControllerTest {

    private static final String STATUS_URL = "/internal/v1/cameras/7/status";

    @Mock
    private CameraStatusService cameraStatusService;

    @Mock
    private DesiredCameraService desiredCameraService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        // 실제 앱처럼 공통 오류 처리(FNC-C-027)를 붙여 상태 코드와 오류 응답 code를 함께 확인한다
        mockMvc = MockMvcBuilders.standaloneSetup(new CameraInternalController(cameraStatusService, desiredCameraService))
                .setControllerAdvice(new GlobalExceptionHandler(JsonMapper.builder().build()))
                .build();
    }

    @Test
    @DisplayName("FNC-A-027 가동 대상 캠 목록은 [{cameraId, sourceType, sourceUri}] 200")
    void returnsDesiredCameras() throws Exception {
        given(desiredCameraService.findDesiredCameras())
                .willReturn(List.of(new DesiredCameraResponse(7L, CameraSourceType.DUMMY, "dummy/videos/a.mp4")));

        mockMvc.perform(get("/internal/v1/cameras/desired"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].cameraId").value(7))
                .andExpect(jsonPath("$[0].sourceType").value("DUMMY"))
                .andExpect(jsonPath("$[0].sourceUri").value("dummy/videos/a.mp4"));
    }

    @Test
    @DisplayName("FNC-A-025 상태 보고를 받으면 204")
    void returnsNoContent() throws Exception {
        mockMvc.perform(patch(STATUS_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status": "PREPARING", "progress": 45, "errorMessage": null}
                                """))
                .andExpect(status().isNoContent());

        verify(cameraStatusService)
                .reportStatus(7L, new CameraStatusUpdateRequest(CameraStatus.PREPARING, 45, null));
    }

    @Test
    @DisplayName("FNC-A-025 없는 캠이면 404")
    void returnsNotFoundWhenCameraMissing() throws Exception {
        willThrow(new CameraNotFoundException(7L))
                .given(cameraStatusService)
                .reportStatus(eq(7L), any(CameraStatusUpdateRequest.class));

        mockMvc.perform(patch(STATUS_URL).contentType(MediaType.APPLICATION_JSON).content("""
                        {"status": "RUNNING"}
                        """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("FNC-A-025 정의되지 않은 status면 400")
    void returnsBadRequestForUnknownStatus() throws Exception {
        mockMvc.perform(patch(STATUS_URL).contentType(MediaType.APPLICATION_JSON).content("""
                        {"status": "PAUSED"}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));

        verifyNoInteractions(cameraStatusService);
    }

    @Test
    @DisplayName("FNC-A-025 status가 없으면 400")
    void returnsBadRequestWhenStatusMissing() throws Exception {
        mockMvc.perform(patch(STATUS_URL).contentType(MediaType.APPLICATION_JSON).content("""
                        {"progress": 45}
                        """))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(cameraStatusService);
    }

    @Test
    @DisplayName("FNC-A-025 progress가 0~100을 벗어나면 400")
    void returnsBadRequestForProgressOutOfRange() throws Exception {
        mockMvc.perform(patch(STATUS_URL).contentType(MediaType.APPLICATION_JSON).content("""
                        {"status": "PREPARING", "progress": 101}
                        """))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(cameraStatusService);
    }
}
