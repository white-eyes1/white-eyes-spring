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

// 사용자 앱 로그인(#126)이 아직 없어 가짜 사용자(@WithMockUser)로 확인한다.
// 로그인하지 않은 요청은 사용자 앱 권한 표(#126 '넘겨받은 일') 전까지 막지 않으므로 여기서 확인하지 않는다
/** 통계 그래프 조회(FNC-C-012)가 사용자 앱에도 같은 경로로 등록되고 접근 권한 검사를 받는지 확인한다. */
@SpringBootTest
@AutoConfigureMockMvc
class StatSeriesUserAppTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @WithMockUser(roles = "PENDING")
    @DisplayName("FNC-C-012 승인 대기 계정은 403")
    void pending_forbidden() throws Exception {
        mockMvc.perform(seriesRequest())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    // 사용자 앱 테스트 DB에는 지역이 없어, 권한 검사를 통과해 API까지 오면 없는 지역 404가 난다
    @Test
    @WithMockUser(roles = "USER")
    @DisplayName("FNC-C-012 승인된 사용자는 API까지 닿는다(없는 지역이라 404)")
    void user_reachesApi() throws Exception {
        mockMvc.perform(seriesRequest())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REGION_NOT_FOUND"));
    }

    private static MockHttpServletRequestBuilder seriesRequest() {
        return get("/api/v1/stats/series")
                .param("targetType", "REGION")
                .param("targetId", "999999")
                .param("interval", "1h")
                .param("from", "2099-01-01T10:00")
                .param("to", "2099-01-01T11:00");
    }
}
