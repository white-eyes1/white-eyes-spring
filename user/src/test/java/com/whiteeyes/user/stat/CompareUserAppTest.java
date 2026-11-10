package com.whiteeyes.user.stat;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** 기간 비교 분석(FNC-C-020)의 사용자 앱 접근 권한(관리자 앱은 admin CompareAccessTest). */
@SpringBootTest
@AutoConfigureMockMvc
class CompareUserAppTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("FNC-C-020 사용자 앱은 로그인하지 않은 요청에 401")
    void anonymous_unauthorized() throws Exception {
        mockMvc.perform(compareRequest())
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    @WithMockUser(roles = "PENDING")
    @DisplayName("FNC-C-020 승인 대기 계정은 403")
    void pending_forbidden() throws Exception {
        mockMvc.perform(compareRequest())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    private static MockHttpServletRequestBuilder compareRequest() {
        return get("/api/v1/compare")
                .param("targetType", "ZONE")
                .param("targetId", "1")
                .param("from", "2099-01-05")
                .param("to", "2099-01-11")
                .param("basePreset", "LAST_MONTH");
    }
}
