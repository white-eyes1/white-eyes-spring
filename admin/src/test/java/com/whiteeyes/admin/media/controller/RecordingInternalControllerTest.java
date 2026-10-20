package com.whiteeyes.admin.media.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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
import tools.jackson.databind.json.JsonMapper;

import com.whiteeyes.admin.camera.exception.CameraNotFoundException;
import com.whiteeyes.admin.media.dto.RecordingRegisterRequest;
import com.whiteeyes.admin.media.dto.RecordingRegisterResponse;
import com.whiteeyes.admin.media.exception.RecordingRegisterException;
import com.whiteeyes.admin.media.service.RecordingRegisterService;
import com.whiteeyes.common.exception.GlobalExceptionHandler;

@ExtendWith(MockitoExtension.class)
class RecordingInternalControllerTest {

    private static final String RECORDINGS_URL = "/internal/v1/recordings";
    private static final String LIVE_BODY = """
            {"cameraId": 7, "startedAt": "2026-10-12T14:03:00.000+09:00", "endedAt": "2026-10-12T14:04:00.000+09:00",
             "videoPath": "recordings/live/cam7/20261012/cam7_20261012_1403.mp4",
             "boxPath": "recordings/live/cam7/20261012/cam7_20261012_1403.json.gz"}
            """;

    @Mock
    private RecordingRegisterService recordingRegisterService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        // 실제 앱처럼 공통 오류 처리(FNC-C-027)를 붙여 상태 코드와 오류 응답 code를 함께 확인한다
        mockMvc = MockMvcBuilders.standaloneSetup(new RecordingInternalController(recordingRegisterService))
                .setControllerAdvice(new GlobalExceptionHandler(JsonMapper.builder().build()))
                .build();
    }

    @Test
    @DisplayName("FNC-A-026 녹화를 등록하면 201과 id")
    void returnsCreatedWithId() throws Exception {
        given(recordingRegisterService.registerRecording(any(RecordingRegisterRequest.class)))
                .willReturn(new RecordingRegisterResponse(900L));

        mockMvc.perform(post(RECORDINGS_URL).contentType(MediaType.APPLICATION_JSON).content(LIVE_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(900));
    }

    @Test
    @DisplayName("FNC-A-026 필수 항목이 없으면 400")
    void returnsBadRequestWhenRequiredFieldMissing() throws Exception {
        mockMvc.perform(post(RECORDINGS_URL).contentType(MediaType.APPLICATION_JSON).content("""
                        {"cameraId": 7, "startedAt": "2026-10-12T14:03:00.000+09:00", "videoPath": "dummy/videos/a.mp4"}
                        """))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(recordingRegisterService);
    }

    @Test
    @DisplayName("FNC-A-026 경로가 255자를 넘으면 400")
    void returnsBadRequestWhenPathTooLong() throws Exception {
        String longVideoPath = "dummy/videos/" + "a".repeat(250) + ".mp4";

        mockMvc.perform(post(RECORDINGS_URL).contentType(MediaType.APPLICATION_JSON).content("""
                        {"cameraId": 7, "startedAt": "2026-10-12T14:03:00.000+09:00",
                         "videoPath": "%s", "boxPath": "dummy/boxes/a.json.gz"}
                        """.formatted(longVideoPath)))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(recordingRegisterService);
    }

    @Test
    @DisplayName("FNC-A-026 없는 캠이면 404")
    void returnsNotFoundWhenCameraMissing() throws Exception {
        willThrow(new CameraNotFoundException(7L))
                .given(recordingRegisterService)
                .registerRecording(any(RecordingRegisterRequest.class));

        mockMvc.perform(post(RECORDINGS_URL).contentType(MediaType.APPLICATION_JSON).content(LIVE_BODY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("FNC-A-026 경로·endedAt 규칙을 어기면 400")
    void returnsBadRequestWhenRuleViolated() throws Exception {
        willThrow(new RecordingRegisterException("라이브 캠 녹화는 endedAt이 필요합니다."))
                .given(recordingRegisterService)
                .registerRecording(any(RecordingRegisterRequest.class));

        mockMvc.perform(post(RECORDINGS_URL).contentType(MediaType.APPLICATION_JSON).content(LIVE_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.message").value("라이브 캠 녹화는 endedAt이 필요합니다."));
    }
}
