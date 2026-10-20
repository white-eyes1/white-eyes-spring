/*
 * [해석본] 원본: user/src/test/java/com/whiteeyes/user/account/controller/SignupPageControllerTest.java (feature/30-signup 브랜치)
 * 관련 이슈: #30(FNC-U-002), 화면: SCR-U-001
 *
 * [무엇] /signup 화면이 그려지는지, 화면이 쓰는 JS 파일이 사용자 앱에서 내려가는지 확인한다.
 * [보지 못하는 것] JS 동작(확인 후 가입하기 켜짐, 409 표시 등). 저장소에 JS 테스트 도구가 없어서 PR 작성 때
 *   가짜 DOM으로 따로 돌려 봤고(저장소에는 넣지 않음), 실제 브라우저 확인은 하지 못했다(PR 본문).
 */
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
                // [해석] 가입하기가 처음에 꺼져 있는 것은 서버가 그린 HTML에서 확인할 수 있다.
                .andExpect(content().string(containsString("<button type=\"submit\" id=\"signup-submit\" disabled>")))
                // [해석] Thymeleaf가 @{/}를 "/"로 바꾼 결과. api.js가 이 값을 읽는다. 템플릿 문법이 틀리면 이 요청 자체가 실패한다.
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
