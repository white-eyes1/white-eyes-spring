/*
 * [해석본] 원본: admin/src/test/java/com/whiteeyes/admin/media/controller/RecordingInternalControllerTest.java
 * 관련 이슈: #17(FNC-A-026), API: POST /internal/v1/recordings
 *
 * [무엇] HTTP 입구 테스트: 201 + {id}(정상), 400(형식 오류 2가지, 규칙 위반), 404(없는 캠).
 * [왜 standaloneSetup인가] #15·#16 컨트롤러 테스트와 같은 이유. @WebMvcTest는 AdminApplication의 @EnableJpaRepositories 때문에
 *   JPA까지 띄우려다 실패한다. standaloneSetup은 컨트롤러 하나만 올려서 빠르고, @Valid 검증·@ResponseStatus는 그대로 동작한다.
 * [서비스는 가짜] 판단 로직은 서비스 테스트가 본다. 여기서는 '서비스가 던진 예외가 맞는 상태 코드로 바뀌는지'만 본다.
 */
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

import com.whiteeyes.admin.camera.exception.CameraNotFoundException;
import com.whiteeyes.admin.media.dto.RecordingRegisterRequest;
import com.whiteeyes.admin.media.dto.RecordingRegisterResponse;
import com.whiteeyes.admin.media.exception.RecordingRegisterException;
import com.whiteeyes.admin.media.service.RecordingRegisterService;

@ExtendWith(MockitoExtension.class)
class RecordingInternalControllerTest {

    private static final String RECORDINGS_URL = "/internal/v1/recordings";
    // [해석] api/analyzer.md 요청 예시(라이브 녹화 1분 파일)를 거의 그대로 옮긴 정상 본문.
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
        mockMvc = MockMvcBuilders.standaloneSetup(new RecordingInternalController(recordingRegisterService))
                .build();
    }

    // [해석] 정상: 201 Created + 본문 {"id": 900}. jsonPath("$.id"): 응답 JSON의 id 칸 값을 꺼내 비교한다.
    @Test
    @DisplayName("FNC-A-026 녹화를 등록하면 201과 id")
    void returnsCreatedWithId() throws Exception {
        given(recordingRegisterService.registerRecording(any(RecordingRegisterRequest.class)))
                .willReturn(new RecordingRegisterResponse(900L));

        mockMvc.perform(post(RECORDINGS_URL).contentType(MediaType.APPLICATION_JSON).content(LIVE_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(900));
    }

    // [해석] boxPath가 빠진 본문 → @NotBlank 위반 → 400. 서비스까지 가지 않았는지(verifyNoInteractions)도 본다.
    @Test
    @DisplayName("FNC-A-026 필수 항목이 없으면 400")
    void returnsBadRequestWhenRequiredFieldMissing() throws Exception {
        mockMvc.perform(post(RECORDINGS_URL).contentType(MediaType.APPLICATION_JSON).content("""
                        {"cameraId": 7, "startedAt": "2026-10-12T14:03:00.000+09:00", "videoPath": "dummy/videos/a.mp4"}
                        """))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(recordingRegisterService);
    }

    // [해석] 길이 255 제한(tables.md VARCHAR(255)). "dummy/videos/"(13자) + 250자 + ".mp4"(4자) = 267자.
    //        경로 모양 자체는 규칙에 맞게 만들어, 길이 규칙(@Size)만 걸리게 했다.
    //        """…""".formatted(값): 텍스트 블록 안의 %s 자리에 값을 끼워 넣는다.
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

    // [해석] 서비스가 CameraNotFoundException(#15의 예외)을 던지면 404. 예외에 붙은 @ResponseStatus(NOT_FOUND)가 동작하는지 확인.
    @Test
    @DisplayName("FNC-A-026 없는 캠이면 404")
    void returnsNotFoundWhenCameraMissing() throws Exception {
        willThrow(new CameraNotFoundException(7L))
                .given(recordingRegisterService)
                .registerRecording(any(RecordingRegisterRequest.class));

        mockMvc.perform(post(RECORDINGS_URL).contentType(MediaType.APPLICATION_JSON).content(LIVE_BODY))
                .andExpect(status().isNotFound());
    }

    // [해석] 서비스가 RecordingRegisterException(경로·endedAt 규칙 위반)을 던지면 400. 메시지 내용은 아무거나 상관없다.
    @Test
    @DisplayName("FNC-A-026 경로·endedAt 규칙을 어기면 400")
    void returnsBadRequestWhenRuleViolated() throws Exception {
        willThrow(new RecordingRegisterException("라이브 캠 녹화는 endedAt이 필요합니다."))
                .given(recordingRegisterService)
                .registerRecording(any(RecordingRegisterRequest.class));

        mockMvc.perform(post(RECORDINGS_URL).contentType(MediaType.APPLICATION_JSON).content(LIVE_BODY))
                .andExpect(status().isBadRequest());
    }
}
