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

// 역할별 동작은 가짜 사용자(@WithMockUser)로 확인한다. 실제 로그인 세션으로 보는 권한 시험은 UserAccessControlTest
/** 이용량 증감 요약(FNC-C-019)가 사용자 앱에도 같은 경로로 등록되고 접근 권한 검사를 받는지 확인한다. */
@SpringBootTest
@AutoConfigureMockMvc
class TrendUserAppTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("FNC-C-019 로그인하지 않은 요청은 401")
    void anonymous_unauthorized() throws Exception {
        mockMvc.perform(targetRequest())
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    @WithMockUser(roles = "PENDING")
    @DisplayName("FNC-C-019 승인 대기 계정은 403")
    void pending_forbidden() throws Exception {
        mockMvc.perform(targetRequest())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    // 사용자 앱 테스트 DB에는 지역이 없어, 권한 검사를 통과해 API까지 오면 없는 지역 404가 난다
    @Test
    @WithMockUser(roles = "USER")
    @DisplayName("FNC-C-019 승인된 사용자는 API까지 닿는다(없는 지역이라 404)")
    void user_reachesApi() throws Exception {
        mockMvc.perform(targetRequest())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REGION_NOT_FOUND"));
    }

    private static MockHttpServletRequestBuilder targetRequest() {
        return get("/api/v1/dashboard/trend")
                .param("targetType", "REGION")
                .param("targetId", "999999");
    }
}
