package com.whiteeyes.admin.stat.controller;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
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

/** 공통 틀(#136)이 관리자 앱 화면에서 그려지는지 확인한다: SCR-A-013 헤더, FNC-C-003 CSRF meta, FNC-C-025 범례 자리. */
@Import(AdminTestUserConfig.class)
@SpringBootTest
@AutoConfigureMockMvc
class DashboardPageControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("SCR-A-013 로그인한 관리자의 /stats 화면에 CSRF·컨텍스트 경로 meta와 헤더 탭이 있다")
    void statsPage_rendersFrame() throws Exception {
        mockMvc.perform(get("/stats"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("<meta name=\"_csrf\"")))
                .andExpect(content().string(containsString("<meta name=\"context-path\"")))
                .andExpect(content().string(containsString("/css/we.css")))
                .andExpect(content().string(containsString("대시보드")))
                .andExpect(content().string(containsString("통계·기간 비교")))
                .andExpect(content().string(containsString("이벤트 로그")))
                .andExpect(content().string(containsString("관리")))
                .andExpect(content().string(containsString("id=\"level-legend\"")))
                .andExpect(content().string(containsString("/js/common/header.js")));
    }

    @Test
    @DisplayName("SCR-A-013 대시보드 탭이 선택된 상태로 그려지고 미확인 배지는 숨겨져 있다")
    void statsPage_dashboardTabActive_badgeHidden() throws Exception {
        mockMvc.perform(get("/stats"))
                .andExpect(content().string(containsString("class=\"tab on\"")))
                .andExpect(content().string(containsString("id=\"unchecked-badge\" hidden")));
    }

    @Test
    @DisplayName("SCR-A-013 사용자 메뉴에 로그인 아이디와 내 정보, CSRF가 붙은 로그아웃(POST) 폼이 있다")
    void statsPage_userMenu_hasLogoutForm() throws Exception {
        mockMvc.perform(get("/stats"))
                .andExpect(content().string(containsString("admin01")))
                .andExpect(content().string(containsString("href=\"/me\"")))
                .andExpect(content().string(containsString("action=\"/logout\"")))
                .andExpect(content().string(containsString("name=\"_csrf\"")));
    }

    @Test
    @DisplayName("SCR-A-013 관리자 관리·작업 기록 메뉴는 최고 관리자에게만 보인다")
    void statsPage_superAdminMenus_onlyForSuperAdmin() throws Exception {
        mockMvc.perform(get("/stats"))
                .andExpect(content().string(containsString("구역 관리")))
                .andExpect(content().string(containsString("캠 관리")))
                .andExpect(content().string(containsString("사용자 관리")))
                .andExpect(content().string(not(containsString("관리자 관리"))))
                .andExpect(content().string(not(containsString("작업 기록"))));

        mockMvc.perform(get("/stats").with(user("super01").roles("SUPER_ADMIN")))
                .andExpect(content().string(containsString("관리자 관리")))
                .andExpect(content().string(containsString("작업 기록")));
    }

    @Test
    @DisplayName("SCR-C-003 로그인한 관리자의 /stats에 지역 필터·통계 패널·지도·캠 패널·지역 등록 버튼이 있다")
    void statsPage_admin_hasAllPanels() throws Exception {
        mockMvc.perform(get("/stats"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"filter-sido\"")))
                .andExpect(content().string(containsString("id=\"filter-emd\"")))
                .andExpect(content().string(containsString("id=\"stat-panel\"")))
                .andExpect(content().string(containsString("id=\"zone-donut\"")))
                .andExpect(content().string(containsString("id=\"dash-map\"")))
                .andExpect(content().string(containsString("id=\"deck-cards\"")))
                .andExpect(content().string(containsString("id=\"stream-status\"")))
                .andExpect(content().string(containsString("혼잡도 높은 순")))
                .andExpect(content().string(containsString("지역 등록·수정")))
                .andExpect(content().string(containsString("/js/pages/stats-main.js")))
                .andExpect(content().string(containsString("/css/stats-main.css")));
    }

    @Test
    @DisplayName("SCR-C-003 실제 화면에는 시안 표시용 data-api·data-fnc 속성과 .anno 요소를 옮기지 않는다")
    void statsPage_noDesignAnnotations() throws Exception {
        mockMvc.perform(get("/stats"))
                .andExpect(content().string(not(containsString("data-api"))))
                .andExpect(content().string(not(containsString("data-fnc"))))
                .andExpect(content().string(not(containsString("anno"))));
    }

    @Test
    @DisplayName("SCR-C-003 로그인하지 않은 요청은 로그인 화면으로 보낸다")
    void statsPage_anonymous_redirectsToLogin() throws Exception {
        mockMvc.perform(get("/admin/stats").contextPath("/admin").with(anonymous()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/login"));
    }
}
