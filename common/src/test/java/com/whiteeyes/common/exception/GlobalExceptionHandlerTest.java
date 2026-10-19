package com.whiteeyes.common.exception;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = ErrorTestController.class)
class GlobalExceptionHandlerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("FNC-C-027 검증 오류는 400 + fieldErrors")
    void validationError() throws Exception {
        mockMvc.perform(post("/api/v1/test/validation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.message").value("입력값을 확인해 주세요."))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("name"))
                .andExpect(jsonPath("$.fieldErrors[0].message").value("필수입니다."));
    }

    @Test
    @DisplayName("FNC-C-027 읽을 수 없는 요청 본문은 400")
    void unreadableBody() throws Exception {
        mockMvc.perform(post("/api/v1/test/validation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.fieldErrors").doesNotExist());
    }

    @Test
    @DisplayName("FNC-C-027 없는 대상은 404, 업무 예외의 메시지를 그대로 보냄")
    void notFound() throws Exception {
        mockMvc.perform(get("/api/v1/test/not-found"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("구역을 찾을 수 없습니다."))
                .andExpect(jsonPath("$.fieldErrors").doesNotExist());
    }

    @Test
    @DisplayName("FNC-C-027 경로 변수가 있는 API의 오류도 본문은 오류 응답 형식만 담음")
    void notFoundWithPathVariable() throws Exception {
        mockMvc.perform(get("/api/v1/test/zones/3"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("구역을 찾을 수 없습니다."))
                .andExpect(jsonPath("$.id").doesNotExist())
                .andExpect(jsonPath("$.error").doesNotExist());
    }

    @Test
    @DisplayName("FNC-C-027 매개변수 검증 오류는 400 + fieldErrors")
    void methodValidationError() throws Exception {
        mockMvc.perform(get("/api/v1/test/positive/0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("id"))
                .andExpect(jsonPath("$.fieldErrors[0].message").value("1 이상이어야 합니다."));
    }

    @Test
    @DisplayName("FNC-C-027 Spring이 상태를 정한 403은 FORBIDDEN 코드로 응답")
    void springStatusForbidden() throws Exception {
        mockMvc.perform(get("/api/v1/test/forbidden"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.message").value("권한이 없습니다."));
    }

    @Test
    @DisplayName("FNC-C-027 형식이 틀린 값은 400, 내부 변환 메시지를 보내지 않음")
    void typeMismatch() throws Exception {
        mockMvc.perform(get("/api/v1/test/page-size").param("size", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("size"))
                .andExpect(content().string(not(containsString("NumberFormatException"))));
    }

    @Test
    @DisplayName("FNC-C-027 필수 값이 빠지면 400 + fieldErrors")
    void missingParameter() throws Exception {
        mockMvc.perform(get("/api/v1/test/page-size"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("size"));
    }

    @Test
    @DisplayName("FNC-C-027 다른 서버 연결 실패는 502, 주소를 보내지 않음")
    void resourceAccessFailure() throws Exception {
        mockMvc.perform(get("/api/v1/test/analyzer"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("EXTERNAL_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value("외부 서버에 연결할 수 없습니다."))
                .andExpect(content().string(not(containsString("10.0.0.5"))));
    }

    @Test
    @DisplayName("FNC-C-027 중복은 409")
    void duplicate() throws Exception {
        mockMvc.perform(get("/api/v1/test/duplicate"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE"))
                .andExpect(jsonPath("$.message").value("이미 존재합니다."));
    }

    @Test
    @DisplayName("FNC-C-027 상태 충돌은 409")
    void statusConflict() throws Exception {
        mockMvc.perform(get("/api/v1/test/status-conflict"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATUS_CONFLICT"));
    }

    @Test
    @DisplayName("FNC-C-027 외부 연결 실패는 502")
    void externalUnavailable() throws Exception {
        mockMvc.perform(get("/api/v1/test/external"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("EXTERNAL_UNAVAILABLE"));
    }

    @Test
    @DisplayName("FNC-C-027 그 외 예외는 500, 내용은 응답에 보내지 않음")
    void unexpected() throws Exception {
        mockMvc.perform(get("/api/v1/test/unexpected"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(content().string(not(containsString("내부 비밀 정보"))));
    }

    @Test
    @DisplayName("FNC-C-027 없는 경로는 404 형식으로 응답")
    void noResource() throws Exception {
        mockMvc.perform(get("/api/v1/nothing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("FNC-C-027 지원하지 않는 요청 방식은 405 형식으로 응답")
    void methodNotAllowed() throws Exception {
        mockMvc.perform(post("/api/v1/test/not-found"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }

    @Test
    @DisplayName("FNC-C-027 내부 API(/internal/)도 JSON으로 응답")
    void internalApi() throws Exception {
        mockMvc.perform(get("/internal/v1/test/not-found"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("FNC-C-027 관리자 앱 컨텍스트 경로(/admin) 아래 API도 JSON으로 응답")
    void adminContextPathApi() throws Exception {
        mockMvc.perform(get("/admin/api/v1/test/not-found").contextPath("/admin"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("FNC-C-027 화면 요청은 오류 화면으로 응답")
    void pageError() throws Exception {
        mockMvc.perform(get("/test/page/not-found"))
                .andExpect(status().isNotFound())
                .andExpect(view().name("error"))
                .andExpect(model().attribute("status", 404))
                .andExpect(model().attribute("message", "구역을 찾을 수 없습니다."))
                .andExpect(content().string(containsString("구역을 찾을 수 없습니다.")));
    }

    @Test
    @DisplayName("FNC-C-027 화면 요청의 500도 내용을 보여주지 않음")
    void pageUnexpected() throws Exception {
        mockMvc.perform(get("/test/page/unexpected"))
                .andExpect(status().isInternalServerError())
                .andExpect(view().name("error"))
                .andExpect(content().string(not(containsString("내부 비밀 정보"))));
    }
}
