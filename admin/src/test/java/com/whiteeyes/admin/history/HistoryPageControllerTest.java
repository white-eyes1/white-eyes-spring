package com.whiteeyes.admin.history;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.whiteeyes.admin.security.AdminTestUserConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/** 작업 기록 화면 SCR-A-012의 FNC-A-053 부분(필터 줄, 작업 기록 표, 페이지)이 그려지는지 확인한다. */
@Import(AdminTestUserConfig.class)
@SpringBootTest
@AutoConfigureMockMvc
class HistoryPageControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("FNC-A-053 SCR-A-012 /histories에 필터(기간·계정 종류·아이디·행동)·조회 버튼·표 머리·화면 JS·CSS가 있고 관리 탭이 선택된다")
    void historiesPage_rendersListParts() throws Exception {
        mockMvc.perform(get("/histories").with(user("super01").roles("SUPER_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("작업 기록")))
                .andExpect(content().string(containsString("id=\"filter-from\"")))
                .andExpect(content().string(containsString("id=\"filter-to\"")))
                .andExpect(content().string(containsString("id=\"filter-actor-type\"")))
                .andExpect(content().string(containsString("id=\"filter-login-id\"")))
                .andExpect(content().string(containsString("id=\"filter-action\"")))
                .andExpect(content().string(containsString("id=\"history-search\"")))
                .andExpect(content().string(containsString("id=\"history-rows\"")))
                .andExpect(content().string(containsString("id=\"history-total\"")))
                .andExpect(content().string(containsString("시각")))
                .andExpect(content().string(containsString("행동")))
                .andExpect(content().string(containsString("/js/admin/pages/histories.js")))
                .andExpect(content().string(containsString("/css/admin/histories.css")))
                .andExpect(content().string(containsString("<meta name=\"_csrf\"")))
                .andExpect(content().string(containsString("class=\"tab on\"")));
    }

    @Test
    @DisplayName("FNC-A-053 관리자(ADMIN)가 /histories를 열면 403")
    void historiesPage_admin_forbidden() throws Exception {
        mockMvc.perform(get("/histories")).andExpect(status().isForbidden());
    }
}
