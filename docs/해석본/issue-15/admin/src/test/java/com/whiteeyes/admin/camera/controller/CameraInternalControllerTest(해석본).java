/*
 * [해석본] 원본: admin/src/test/java/com/whiteeyes/admin/camera/controller/CameraInternalControllerTest.java (feature/15-cam-status-report 브랜치)
 * 관련 이슈: #15(FNC-A-025), API: PATCH /internal/v1/cameras/{id}/status
 *
 * [무엇] HTTP 입구 테스트: 204(정상), 404(없는 캠), 400(형식 오류 3가지).
 * [왜 standaloneSetup인가] @WebMvcTest를 쓰면 AdminApplication의 @EnableJpaRepositories 때문에 JPA까지 띄우려다 실패한다.
 *   standaloneSetup은 컨트롤러 하나만 가짜 서블릿 환경에 올려서 그 문제가 없고, JSON 변환·@Valid 검증·@ResponseStatus(404)는
 *   그대로 동작한다.
 * [401이 없는 이유] 토큰 검사 필터가 아직 없어 테스트할 대상이 없다(PR #93 '하지 않은 것').
 */
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

    // [해석] 캠 7번의 상태 보고 주소. standaloneSetup에는 /admin 컨텍스트 경로가 없으므로 컨트롤러 경로 그대로 쓴다.
    private static final String STATUS_URL = "/internal/v1/cameras/7/status";

    @Mock
    private CameraStatusService cameraStatusService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new CameraInternalController(cameraStatusService))
                .build();
    }

    // [해석] api/analyzer.md의 요청 예시를 그대로 보냄 → 204 + 서비스가 '캠 7, PREPARING, 45, null'로 정확히 불렸는가.
    //        record라 equals가 값 비교라서 new CameraStatusUpdateRequest(...)와 바로 비교할 수 있다.
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

    // [해석] 서비스가 CameraNotFoundException을 던지면 → 404. 예외에 붙인 @ResponseStatus(NOT_FOUND)가 동작하는지 확인.
    //        {"status": "RUNNING"}처럼 선택 항목을 뺀 최소 본문이 통과한다는 것도 함께 확인된다.
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

    // [해석] 합의 3(잘못된 값은 400)의 세 가지 경우. 세 테스트 모두 verifyNoInteractions로 '서비스까지 가지 않았음'을 본다.
    //        PAUSED: CameraStatus enum에 없는 값 → JSON 변환 단계에서 실패 → 400.
    @Test
    @DisplayName("FNC-A-025 정의되지 않은 status면 400")
    void returnsBadRequestForUnknownStatus() throws Exception {
        mockMvc.perform(patch(STATUS_URL).contentType(MediaType.APPLICATION_JSON).content("""
                        {"status": "PAUSED"}
                        """))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(cameraStatusService);
    }

    // [해석] status 누락 → @NotNull 위반 → 400.
    @Test
    @DisplayName("FNC-A-025 status가 없으면 400")
    void returnsBadRequestWhenStatusMissing() throws Exception {
        mockMvc.perform(patch(STATUS_URL).contentType(MediaType.APPLICATION_JSON).content("""
                        {"progress": 45}
                        """))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(cameraStatusService);
    }

    // [해석] progress 101 → @Max(100) 위반 → 400. 100은 정상이라 경계 바로 밖인 101을 골랐다.
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
