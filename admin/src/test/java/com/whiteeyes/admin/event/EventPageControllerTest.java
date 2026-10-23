package com.whiteeyes.admin.event;

import static org.hamcrest.Matchers.containsString;
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

/** 이벤트 로그 화면 SCR-A-010의 FNC-A-036 부분(요약 카드, 필터, 이벤트 표)이 그려지는지 확인한다. */
@Import(AdminTestUserConfig.class)
@SpringBootTest
@AutoConfigureMockMvc
class EventPageControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("SCR-A-010 /events에 요약 카드 4개, 상태·종류·검색 필터, 표 머리, 화면 JS·CSS가 있고 이벤트 로그 탭이 선택된다")
    void eventsPage_rendersListParts() throws Exception {
        mockMvc.perform(get("/events"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("이벤트 로그")))
                .andExpect(content().string(containsString("id=\"summary-total\"")))
                .andExpect(content().string(containsString("id=\"summary-unchecked\"")))
                .andExpect(content().string(containsString("id=\"summary-checked\"")))
                .andExpect(content().string(containsString("id=\"summary-resolved\"")))
                .andExpect(content().string(containsString("id=\"filter-status\"")))
                .andExpect(content().string(containsString("id=\"filter-type\"")))
                .andExpect(content().string(containsString("id=\"filter-keyword\"")))
                .andExpect(content().string(containsString("id=\"event-rows\"")))
                .andExpect(content().string(containsString("발생 시각")))
                .andExpect(content().string(containsString("처리 상태")))
                .andExpect(content().string(containsString("/js/admin/pages/events.js")))
                .andExpect(content().string(containsString("/css/admin/events.css")))
                .andExpect(content().string(containsString("<meta name=\"_csrf\"")))
                .andExpect(content().string(containsString("class=\"tab on\"")));
    }

    @Test
    @DisplayName("SCR-A-011 /events에 이벤트 상세 창(정보·처리 이력·닫기)이 들어 있다")
    void eventsPage_rendersDetailModal() throws Exception {
        mockMvc.perform(get("/events"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"detail-modal\"")))
                .andExpect(content().string(containsString("id=\"detail-info\"")))
                .andExpect(content().string(containsString("id=\"detail-histories\"")))
                .andExpect(content().string(containsString("처리 이력")))
                .andExpect(content().string(containsString("data-close")));
    }
}
