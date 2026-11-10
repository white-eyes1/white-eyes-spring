package com.whiteeyes.admin.notification;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 관리자 알림 스트림(FNC-A-048)의 비로그인 요청. AdminTestUserConfig를 붙이면 모든 요청에 ADMIN 사용자가 붙어
 * 비로그인을 만들 수 없으므로, 로그인하지 않은 요청 확인은 이 클래스에서 따로 한다(.claude/rules/testing.md).
 */
@SpringBootTest(properties = "spring.jpa.open-in-view=false")
@AutoConfigureMockMvc
class NotificationAnonymousTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("FNC-A-048 로그인하지 않으면 알림 스트림은 401")
    void streamRejectsAnonymous() throws Exception {
        mockMvc.perform(get("/api/v1/notifications/stream")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("FNC-A-048 unchecked-count는 로그인하지 않으면 401")
    void uncheckedCountRequiresLogin() throws Exception {
        mockMvc.perform(get("/api/v1/events/unchecked-count")).andExpect(status().isUnauthorized());
    }
}
