package com.whiteeyes.admin.security;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * 내부 API 접근 검사(FNC-C-003): /internal/** 은 내부 포트(8082)에서 X-Internal-Token으로만 인증하고 세션은 쓰지 않는다.
 * MockMvc에는 포트가 없어 요청의 localPort로 어느 포트로 들어왔는지 흉내 낸다. 실제 두 포트 기동은 InternalPortServerTest가 본다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class InternalAccessTest {

    private static final String CONTEXT_PATH = "/admin";
    private static final String TOKEN_HEADER = "X-Internal-Token";
    // 테스트용 application.yml의 값(가짜)
    private static final String TOKEN = "test-internal-token";
    private static final int WEB_PORT = 8081;
    private static final int INTERNAL_PORT = 8082;

    @Autowired
    private MockMvc mockMvc;

    private MockHttpServletRequestBuilder post(String path, int localPort) {
        return MockMvcRequestBuilders.request(HttpMethod.POST, CONTEXT_PATH + path)
                .contextPath(CONTEXT_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .content("[]")
                .with(request -> {
                    request.setLocalPort(localPort);
                    return request;
                });
    }

    @Test
    @DisplayName("FNC-C-003 내부 포트에서 올바른 토큰을 보내면 로그인·CSRF 없이 통과한다")
    void internalPort_validToken_allowed() throws Exception {
        mockMvc.perform(post("/internal/v1/results", INTERNAL_PORT).header(TOKEN_HEADER, TOKEN))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("FNC-C-003 토큰이 없으면 공통 오류 형식의 401이다")
    void internalPort_noToken_unauthorized() throws Exception {
        mockMvc.perform(post("/internal/v1/results", INTERNAL_PORT))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("FNC-C-003 토큰 값이 다르면 401이다")
    void internalPort_wrongToken_unauthorized() throws Exception {
        mockMvc.perform(post("/internal/v1/results", INTERNAL_PORT).header(TOKEN_HEADER, TOKEN + "-x"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("FNC-C-003 관리자로 로그인한 세션이 있어도 토큰이 없으면 401이다(세션을 쓰지 않음)")
    void internalPort_sessionWithoutToken_unauthorized() throws Exception {
        mockMvc.perform(post("/internal/v1/results", INTERNAL_PORT).with(user("admin01").roles("SUPER_ADMIN")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("FNC-C-003 웹 포트(8081)로는 올바른 토큰이 있어도 /internal/** 을 받지 않는다")
    void webPort_internalPath_notFound() throws Exception {
        mockMvc.perform(post("/internal/v1/results", WEB_PORT).header(TOKEN_HEADER, TOKEN))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("FNC-C-003 %로 인코딩해 쓴 /internal 경로도 웹 포트로는 받지 않는다")
    void webPort_encodedInternalPath_notFound() throws Exception {
        // 문자열로 넘기면 %가 한 번 더 인코딩되므로 URI로 그대로 넘긴다
        mockMvc.perform(MockMvcRequestBuilders.request(HttpMethod.POST, java.net.URI.create("/admin/%69nternal/v1/results"))
                        .contextPath(CONTEXT_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[]")
                        .header(TOKEN_HEADER, TOKEN)
                        .with(request -> {
                            request.setLocalPort(WEB_PORT);
                            return request;
                        }))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("FNC-C-003 내부 포트(8082)로는 /internal/** 이 아닌 경로를 받지 않는다")
    void internalPort_otherPath_notFound() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get(CONTEXT_PATH + "/api/v1/manage/cameras")
                        .contextPath(CONTEXT_PATH)
                        .with(user("admin01").roles("ADMIN"))
                        .with(request -> {
                            request.setLocalPort(INTERNAL_PORT);
                            return request;
                        }))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("FNC-C-003 내부 포트로도 로그인 화면은 받지 않는다")
    void internalPort_loginPage_notFound() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get(CONTEXT_PATH + "/login")
                        .contextPath(CONTEXT_PATH)
                        .with(request -> {
                            request.setLocalPort(INTERNAL_PORT);
                            return request;
                        }))
                .andExpect(status().isNotFound());
    }
}
