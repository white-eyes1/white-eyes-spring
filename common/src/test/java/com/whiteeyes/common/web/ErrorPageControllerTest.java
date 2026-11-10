package com.whiteeyes.common.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import jakarta.servlet.RequestDispatcher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 보안 필터가 넘긴 403 같은 서버 오류 처리(/error)는 Accept 헤더와 상관없이 경로로 화면·API를 가른다(FNC-C-003).
 * 실제 오류 전달(sendError → /error)은 MockMvc가 하지 않으므로, 서버가 채우는 오류 속성을 붙여 /error를 직접 부른다.
 */
@WebMvcTest(controllers = ErrorPageController.class)
@Import(ErrorPageViewResolver.class)
class ErrorPageControllerTest {

    private static final String CONTEXT_PATH = "/admin";

    @Autowired
    private MockMvc mockMvc;

    // 관리자 앱(컨텍스트 경로 /admin)에서 originalUri 요청이 403으로 오류 처리에 넘어온 상태
    private MockHttpServletRequestBuilder forbiddenErrorOf(String originalUri) {
        return get(CONTEXT_PATH + "/error")
                .contextPath(CONTEXT_PATH)
                .requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 403)
                .requestAttr(RequestDispatcher.ERROR_REQUEST_URI, originalUri);
    }

    @ParameterizedTest(name = "Accept={0}")
    @ValueSource(strings = {MediaType.ALL_VALUE, MediaType.APPLICATION_JSON_VALUE, MediaType.TEXT_HTML_VALUE})
    @DisplayName("FNC-C-003 화면 경로의 403은 Accept와 상관없이 공통 오류 화면이다")
    void screenPath_anyAccept_errorView(String accept) throws Exception {
        mockMvc.perform(forbiddenErrorOf("/admin/admins").header(HttpHeaders.ACCEPT, accept))
                .andExpect(status().isForbidden())
                .andExpect(view().name("error"))
                .andExpect(model().attribute("message", "권한이 없습니다."));
    }

    @Test
    @DisplayName("FNC-C-003 Accept가 없어도 화면 경로의 403은 공통 오류 화면이다")
    void screenPath_noAccept_errorView() throws Exception {
        mockMvc.perform(forbiddenErrorOf("/admin/histories"))
                .andExpect(status().isForbidden())
                .andExpect(view().name("error"));
    }

    @Test
    @DisplayName("FNC-C-003 API 경로의 오류는 브라우저 Accept여도 JSON이다")
    void apiPath_htmlAccept_json() throws Exception {
        mockMvc.perform(forbiddenErrorOf("/admin/api/v1/admins").accept(MediaType.TEXT_HTML))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(403));
    }
}
