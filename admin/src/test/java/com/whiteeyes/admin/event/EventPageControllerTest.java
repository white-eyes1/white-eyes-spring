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

/** 이벤트 로그 화면 SCR-A-010의 FNC-A-036 부분(요약 카드, 필터, 이벤트 표)과 상세 창 SCR-A-011의 FNC-A-037·040 부분이 그려지는지 확인한다. */
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

    @Test
    @DisplayName("FNC-A-040 SCR-A-011 상세 창에 발생 시점 영상·박스 캔버스·인원 띠·다시보기 크게 보기 링크와 영상 스타일이 있다")
    void eventsPage_rendersDetailVideo() throws Exception {
        mockMvc.perform(get("/events"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"detail-video\"")))
                .andExpect(content().string(containsString("발생 시점 영상")))
                .andExpect(content().string(containsString("id=\"detail-video-player\"")))
                .andExpect(content().string(containsString("id=\"detail-video-boxes\"")))
                .andExpect(content().string(containsString("id=\"detail-video-notice\"")))
                .andExpect(content().string(containsString("id=\"detail-video-chart\"")))
                .andExpect(content().string(containsString("id=\"detail-video-axis\"")))
                .andExpect(content().string(containsString("id=\"detail-open-playback\"")))
                .andExpect(content().string(containsString("다시보기 화면에서 크게 보기")))
                .andExpect(content().string(containsString("/css/admin/event-video.css")));
    }

    @Test
    @DisplayName("FNC-A-038 /events 표에 조치 열이 있고, 조치 완료 창(항목 5개·메모 200자·저장 버튼)이 들어 있다")
    void eventsPage_rendersActionColumnAndModal() throws Exception {
        mockMvc.perform(get("/events"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("columnheader\">조치<")))
                .andExpect(content().string(containsString("id=\"action-modal\"")))
                .andExpect(content().string(containsString("조치 완료 처리")))
                .andExpect(content().string(containsString("value=\"ONSITE_OK\"")))
                .andExpect(content().string(containsString("value=\"STAFF\"")))
                .andExpect(content().string(containsString("value=\"FALSE_ALARM\"")))
                .andExpect(content().string(containsString("value=\"CAM_CHECK\"")))
                .andExpect(content().string(containsString("value=\"OTHER\"")))
                .andExpect(content().string(containsString("id=\"action-note\"")))
                .andExpect(content().string(containsString("maxlength=\"200\"")))
                .andExpect(content().string(containsString("id=\"action-save\"")))
                .andExpect(content().string(containsString("조치완료로 저장")));
    }
}
