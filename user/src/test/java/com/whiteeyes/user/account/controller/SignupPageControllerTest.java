package com.whiteeyes.user.account.controller;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class SignupPageControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("SCR-U-001 /signup은 가입 폼을 보이고, 가입하기는 처음에 꺼져 있다")
    void signupPage_rendersForm() throws Exception {
        mockMvc.perform(get("/signup"))
                .andExpect(status().isOk())
                .andExpect(view().name("user/signup"))
                .andExpect(content().string(containsString("id=\"signup-form\"")))
                .andExpect(content().string(containsString("id=\"check-login-id\"")))
                .andExpect(content().string(containsString("<button type=\"submit\" id=\"signup-submit\" disabled>")))
                .andExpect(content().string(containsString("<meta name=\"context-path\" content=\"/\">")))
                .andExpect(content().string(containsString("src=\"/js/pages/signup.js\"")));
    }

    // 정적 파일은 common 모듈에 있어서 사용자 앱에서도 내려가는지 본다
    @ParameterizedTest
    @ValueSource(strings = {"/js/pages/signup.js", "/js/common/api.js"})
    @DisplayName("SCR-U-001 화면이 쓰는 JS 파일을 사용자 앱이 내려준다")
    void signupPage_scriptsServed(String path) throws Exception {
        mockMvc.perform(get(path)).andExpect(status().isOk());
    }
}
