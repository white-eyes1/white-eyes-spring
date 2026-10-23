package com.whiteeyes.admin.stat.controller;

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

/** FNC-A-042: 환경변수 CARD_SNAPSHOT_INTERVAL_SEC를 바꾸면 그 값이 화면으로 전달된다. */
@Import(AdminTestUserConfig.class)
@SpringBootTest(properties = "CARD_SNAPSHOT_INTERVAL_SEC=5")
@AutoConfigureMockMvc
class DashboardSnapshotIntervalTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("FNC-A-042 CARD_SNAPSHOT_INTERVAL_SEC=5이면 화면에 5가 전달된다")
    void statsPage_passesConfiguredInterval() throws Exception {
        mockMvc.perform(get("/stats"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("data-snapshot-interval-sec=\"5\"")));
    }
}
