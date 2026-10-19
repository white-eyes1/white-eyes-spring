package com.whiteeyes.admin.camera.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.whiteeyes.admin.camera.dto.CameraStatusUpdateRequest;
import com.whiteeyes.admin.camera.exception.CameraNotFoundException;
import com.whiteeyes.admin.camera.service.CameraStatusService;
import com.whiteeyes.common.camera.entity.CameraStatus;

@ExtendWith(MockitoExtension.class)
class CameraInternalControllerTest {

    private static final String STATUS_URL = "/internal/v1/cameras/7/status";

    @Mock
    private CameraStatusService cameraStatusService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new CameraInternalController(cameraStatusService))
                .build();
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
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("FNC-A-025 정의되지 않은 status면 400")
    void returnsBadRequestForUnknownStatus() throws Exception {
        mockMvc.perform(patch(STATUS_URL).contentType(MediaType.APPLICATION_JSON).content("""
                        {"status": "PAUSED"}
                        """))
                .andExpect(status().isBadRequest());

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
