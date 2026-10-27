package com.whiteeyes.user.account;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 승인 대기 안내(FNC-U-003)를 가짜 PENDING 사용자(@WithMockUser)로 확인한다. 실제 PENDING 계정 로그인은 UserLoginTest가 본다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PendingAccessTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @WithMockUser(roles = "PENDING")
    @DisplayName("FNC-U-003 검수 기준: PENDING 계정으로 통계 API를 부르면 403")
    void pending_statsApi_forbidden() throws Exception {
        mockMvc.perform(get("/api/v1/meta/levels"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.message").value("권한이 없습니다."));
    }

    @Test
    @WithMockUser(roles = "PENDING")
    @DisplayName("FNC-U-003 아직 없는 통계 API 경로도 PENDING이면 403")
    void pending_futureStatsApi_forbidden() throws Exception {
        mockMvc.perform(get("/api/v1/dashboard/summary"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    @WithMockUser(roles = "PENDING")
    @DisplayName("FNC-U-003 PENDING 계정이 다른 화면으로 가면 승인 대기 화면으로 보낸다")
    void pending_otherPage_redirectsToPending() throws Exception {
        mockMvc.perform(get("/stats"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/pending"));
    }

    @Test
    @WithMockUser(roles = "PENDING")
    @DisplayName("SCR-U-003 PENDING 계정이 /signup으로 가면 승인 대기 화면으로 보낸다")
    void pending_signupPage_redirectsToPending() throws Exception {
        mockMvc.perform(get("/signup"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/pending"));
    }

    @Test
    @DisplayName("FNC-U-002 로그인하지 않은 사용자는 /signup을 열 수 있다")
    void anonymous_signupPage_allowed() throws Exception {
        mockMvc.perform(get("/signup")).andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "PENDING")
    @DisplayName("FNC-U-003 승인 대기 화면에 PENDING 계정이 다시 들어와도 되돌림(루프) 없이 열린다")
    void pending_pendingPage_noLoop() throws Exception {
        mockMvc.perform(get("/pending")).andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "PENDING")
    @DisplayName("FNC-U-003 승인 대기 화면에 안내 문구와 로그아웃 버튼이 있다")
    void pending_pendingPage_showsNoticeAndLogout() throws Exception {
        mockMvc.perform(get("/pending"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("관리자 승인을 기다리고 있습니다")))
                .andExpect(content().string(containsString("action=\"/logout\"")))
                .andExpect(content().string(containsString("type=\"hidden\" name=\"_csrf\"")));
    }

    @Test
    @WithMockUser(roles = "PENDING")
    @DisplayName("FNC-U-003 승인 대기 화면은 공통 화면 틀(head·scripts)을 쓴다")
    void pending_pendingPage_usesCommonLayout() throws Exception {
        mockMvc.perform(get("/pending"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("<title>승인 대기 - White Eyes</title>")))
                .andExpect(content().string(containsString("/css/we.css")))
                .andExpect(content().string(containsString("/js/common/header.js")));
    }

    @Test
    @WithMockUser(roles = "PENDING")
    @DisplayName("FNC-U-003 PENDING 계정도 로그아웃할 수 있다")
    void pending_logout_allowed() throws Exception {
        mockMvc.perform(post("/logout").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    @Test
    @WithMockUser(roles = "USER")
    @DisplayName("FNC-U-003 승인된 사용자는 같은 API를 쓸 수 있다")
    void user_statsApi_allowed() throws Exception {
        mockMvc.perform(get("/api/v1/meta/levels")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("FNC-C-003 로그인하지 않은 통계 API 요청은 401")
    void anonymous_statsApi_unauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/meta/levels"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }
}
