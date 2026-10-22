package com.whiteeyes.admin.camera.controller;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.whiteeyes.admin.security.AdminTestUserConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/** 캠 관리 화면 SCR-A-006·007(FNC-A-018·020·021·022)이 그려지는지 확인한다. */
@Import(AdminTestUserConfig.class)
@SpringBootTest
@AutoConfigureMockMvc
class CameraPageControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("SCR-A-006 /cameras에 캠 표 머리와 등록 버튼, 화면 JS·CSS가 있고 관리 탭이 선택된다")
    void camerasPage_rendersTable() throws Exception {
        mockMvc.perform(get("/cameras"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("캠 관리")))
                .andExpect(content().string(containsString("준비 진행률")))
                .andExpect(content().string(containsString("오류 내용")))
                .andExpect(content().string(containsString("id=\"camera-rows\"")))
                .andExpect(content().string(containsString("id=\"camera-add\"")))
                .andExpect(content().string(containsString("/js/admin/pages/cameras.js")))
                .andExpect(content().string(containsString("/css/admin/cameras.css")))
                .andExpect(content().string(containsString("<meta name=\"_csrf\"")))
                .andExpect(content().string(containsString("class=\"tab on\"")));
    }

    @Test
    @DisplayName("SCR-A-007 캠 등록 창에 구역·이름·입력 방식(더미/라이브)·영상 주소 입력이 있다")
    void camerasPage_rendersRegisterModal() throws Exception {
        mockMvc.perform(get("/cameras"))
                .andExpect(content().string(containsString("id=\"camera-modal\"")))
                .andExpect(content().string(containsString("name=\"zoneId\"")))
                .andExpect(content().string(containsString("name=\"name\"")))
                .andExpect(content().string(containsString("value=\"DUMMY\"")))
                .andExpect(content().string(containsString("value=\"LIVE\"")))
                .andExpect(content().string(containsString("name=\"sourceUri\"")));
    }

    @Test
    @DisplayName("1단계 범위 밖인 실시간 보기·수정·삭제 버튼과 시안용 data-api·anno 속성은 없다")
    void camerasPage_noOutOfScopeParts() throws Exception {
        mockMvc.perform(get("/cameras"))
                .andExpect(content().string(not(containsString("실시간 보기"))))
                .andExpect(content().string(not(containsString("data-api"))))
                .andExpect(content().string(not(containsString("data-fnc"))))
                .andExpect(content().string(not(containsString("anno"))));
    }

    @Test
    @DisplayName("FNC-C-003 로그인하지 않으면 /cameras는 로그인 화면으로 보낸다")
    void camerasPage_anonymous_redirectsToLogin() throws Exception {
        mockMvc.perform(get("/cameras").with(anonymous()))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", containsString("/login")));
    }
}
