package com.whiteeyes.admin.stat.controller;

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

/** 기간 비교 분석 화면 SCR-C-005(FNC-C-020)의 관리자 앱 렌더링 */
@Import(AdminTestUserConfig.class)
@SpringBootTest
@AutoConfigureMockMvc
class ComparePageControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("SCR-C-005 /stats/compare에 조건(대상·대상 기간·기준 기간·비정상 기준), 전체 증감률, 비교표가 있다")
    void comparePage_hasConditionsAndResult() throws Exception {
        mockMvc.perform(get("/stats/compare"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("기간 비교 분석")))
                .andExpect(content().string(containsString("id=\"target-sido\"")))
                .andExpect(content().string(containsString("id=\"target-zone\"")))
                .andExpect(content().string(containsString("id=\"target-from\"")))
                .andExpect(content().string(containsString("id=\"target-to\"")))
                .andExpect(content().string(containsString("지난달 같은 요일")))
                .andExpect(content().string(containsString("지난해 같은 기간")))
                .andExpect(content().string(containsString("직접 지정")))
                .andExpect(content().string(containsString("id=\"threshold\"")))
                .andExpect(content().string(containsString("min=\"5\" max=\"100\"")))
                .andExpect(content().string(containsString("id=\"total-rate\"")))
                .andExpect(content().string(containsString("id=\"compare-grid\"")))
                .andExpect(content().string(containsString("/js/pages/stats-compare.js")))
                .andExpect(content().string(containsString("/css/stats-compare.css")));
    }

    @Test
    @DisplayName("SCR-C-005 헤더의 '통계·기간 비교' 탭이 선택된 상태로 그려진다")
    void comparePage_statsTabActive() throws Exception {
        mockMvc.perform(get("/stats/compare"))
                .andExpect(content().string(containsString("class=\"tab on\" href=\"/stats/compare\"")));
    }

    @Test
    @DisplayName("SCR-C-005 실제 화면에는 시안 표시용 data-api·data-fnc 속성과 .anno 요소를 옮기지 않는다")
    void comparePage_noDesignAnnotations() throws Exception {
        mockMvc.perform(get("/stats/compare"))
                .andExpect(content().string(not(containsString("data-api"))))
                .andExpect(content().string(not(containsString("data-fnc"))))
                .andExpect(content().string(not(containsString("anno"))));
    }

    @Test
    @DisplayName("SCR-C-005 로그인하지 않은 요청은 로그인 화면으로 보낸다")
    void comparePage_anonymous_redirectsToLogin() throws Exception {
        mockMvc.perform(get("/admin/stats/compare").contextPath("/admin").with(anonymous()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/login"));
    }
}
