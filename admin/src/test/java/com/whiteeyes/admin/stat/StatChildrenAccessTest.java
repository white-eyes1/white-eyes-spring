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
/** 여러 지역 비교(FNC-C-014)의 관리자 앱 접근 권한 */
@SpringBootTest
@AutoConfigureMockMvc
class StatChildrenAccessTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("FNC-C-014 관리자 앱은 로그인하지 않은 요청에 401")
    void anonymous_unauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/stats/children")
                        .param("regionId", "1")
                        .param("item", "COUNT")
                        .param("interval", "1h")
                        .param("from", "2099-01-01T10:00")
                        .param("to", "2099-01-01T11:00"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }
}
