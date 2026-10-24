package com.whiteeyes.admin.stat;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

// AdminTestUserConfig를 쓰면 모든 요청에 관리자가 붙으므로, 로그인하지 않은 요청은 이 클래스에서 따로 본다
/** 시간대별 히트맵(FNC-C-011)의 관리자 앱 접근 권한 */
@SpringBootTest
@AutoConfigureMockMvc
class HeatmapAccessTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("FNC-C-011 관리자 앱은 로그인하지 않은 요청에 401")
    void anonymous_unauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/map/heatmap")
                        .param("regionId", "1")
                        .param("from", "2099-01-01T10:00")
                        .param("to", "2099-01-01T11:00")
                        .param("item", "COUNT"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }
}
