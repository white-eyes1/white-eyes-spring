package com.whiteeyes.admin.region.controller;

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

/** SCR-A-004 구역 관리 화면이 공통 틀 위에 그려지는지 확인한다. */
@Import(AdminTestUserConfig.class)
@SpringBootTest
@AutoConfigureMockMvc
class ZonePageControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("SCR-A-004 로그인한 관리자의 /zones 화면에 시·구·동 선택, 지도, 구역 목록, 화면 스크립트가 있다")
    void zonesPage_rendersForAdmin() throws Exception {
        mockMvc.perform(get("/zones"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"region-city\"")))
                .andExpect(content().string(containsString("id=\"region-district\"")))
                .andExpect(content().string(containsString("id=\"region-dong\"")))
                .andExpect(content().string(containsString("id=\"zone-map\"")))
                .andExpect(content().string(containsString("id=\"zone-list\"")))
                .andExpect(content().string(containsString("새 구역 그리기")))
                .andExpect(content().string(containsString("leaflet-geoman")))
                .andExpect(content().string(containsString("/js/admin/pages/zones.js")));
    }

    @Test
    @DisplayName("SCR-A-004 관리 탭이 선택된 상태로 그려지고 시안 표시용 속성은 없다")
    void zonesPage_manageTabActive_noDesignAttributes() throws Exception {
        mockMvc.perform(get("/zones"))
                .andExpect(content().string(containsString("class=\"tab on\"")))
                .andExpect(content().string(not(containsString("data-api"))))
                .andExpect(content().string(not(containsString("data-fnc"))))
                .andExpect(content().string(not(containsString("anno"))));
    }

    @Test
    @DisplayName("SCR-A-004 로그인하지 않은 요청은 로그인 화면으로 보낸다")
    void zonesPage_anonymous_redirectsToLogin() throws Exception {
        mockMvc.perform(get("/zones").with(anonymous()))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/login"));
    }
}
