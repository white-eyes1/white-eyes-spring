package com.whiteeyes.admin.account.controller;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/** 사용자 관리 화면 틀(SCR-A-001, FNC-A-002). */
@SpringBootTest
@AutoConfigureMockMvc
class UserPageControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("SCR-A-001 사용자 관리 화면에 검색 영역·정렬 머리글·안내 영역이 있다")
    void users_rendersSkeleton() throws Exception {
        mockMvc.perform(get("/users"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/users"))
                .andExpect(content().string(containsString("id=\"user-search-form\"")))
                .andExpect(content().string(containsString("data-sort=\"loginId\"")))
                .andExpect(content().string(containsString("data-sort=\"createdAt\"")))
                .andExpect(content().string(containsString("data-sort=\"lastActiveAt\"")))
                .andExpect(content().string(containsString("id=\"user-list-truncated\"")))
                .andExpect(content().string(containsString("id=\"user-pagination\"")));
    }
}
