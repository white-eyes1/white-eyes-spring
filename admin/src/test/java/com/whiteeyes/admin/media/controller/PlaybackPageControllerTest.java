package com.whiteeyes.admin.media.controller;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.whiteeyes.admin.security.AdminTestUserConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/** SCR-A-009 다시보기 화면(FNC-A-047)이 공통 틀 위에 그려지는지 확인한다. */
@Import(AdminTestUserConfig.class)
@SpringBootTest
@AutoConfigureMockMvc
class PlaybackPageControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("SCR-A-009 /playback 화면에 영상, 재생 속도, 1분 통계, 통계 시간축, 화면 스크립트가 있다")
    void playbackPage_rendersForAdmin() throws Exception {
        mockMvc.perform(get("/playback").param("zoneId", "1").param("at", "2026-10-22T10:00:00+09:00"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"pb-video\"")))
                .andExpect(content().string(containsString("id=\"pb-speed\"")))
                .andExpect(content().string(containsString("data-rate=\"4\"")))
                .andExpect(content().string(containsString("id=\"pb-prev-event\"")))
                .andExpect(content().string(containsString("id=\"pb-next-event\"")))
                .andExpect(content().string(containsString("이 시점의 1분 통계")))
                .andExpect(content().string(containsString("id=\"pb-chart\"")))
                .andExpect(content().string(containsString("id=\"pb-guides\"")))
                .andExpect(content().string(containsString("id=\"pb-rec-strip\"")))
                .andExpect(content().string(containsString("data-range=\"today\"")))
                .andExpect(content().string(containsString("data-metric=\"flow\"")))
                .andExpect(content().string(containsString("/css/admin/playback.css")))
                .andExpect(content().string(containsString("/js/admin/pages/playback.js")));
    }

    @Test
    @DisplayName("SCR-A-009 시안 표시용 속성은 옮기지 않는다")
    void playbackPage_noDesignAttributes() throws Exception {
        mockMvc.perform(get("/playback"))
                .andExpect(content().string(not(containsString("data-api"))))
                .andExpect(content().string(not(containsString("data-fnc"))))
                .andExpect(content().string(not(containsString("anno"))));
    }

    @Test
    @DisplayName("SCR-A-009 로그인하지 않은 요청은 로그인 화면으로 보낸다")
    void playbackPage_anonymous_redirectsToLogin() throws Exception {
        mockMvc.perform(get("/playback").with(anonymous()))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/login"));
    }
}
