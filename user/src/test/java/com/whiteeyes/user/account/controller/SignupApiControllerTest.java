package com.whiteeyes.user.account.controller;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.whiteeyes.user.account.service.SignupService;

@ExtendWith(MockitoExtension.class)
class SignupApiControllerTest {

    private static final String CHECK_ID_URL = "/api/v1/signup/check-id";

    @Mock
    private SignupService signupService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new SignupApiController(signupService))
                .build();
    }

    @Test
    @DisplayName("FNC-U-001 사용할 수 있는 아이디면 available=true")
    void checkLoginId_available_true() throws Exception {
        given(signupService.isLoginIdAvailable("hong01")).willReturn(true);

        mockMvc.perform(get(CHECK_ID_URL).param("loginId", "hong01"))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"available": true}
                        """, JsonCompareMode.STRICT));
    }

    @Test
    @DisplayName("FNC-U-001 이미 있는 아이디면 available=false")
    void checkLoginId_taken_false() throws Exception {
        given(signupService.isLoginIdAvailable("hong01")).willReturn(false);

        mockMvc.perform(get(CHECK_ID_URL).param("loginId", "hong01"))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"available": false}
                        """, JsonCompareMode.STRICT));
    }

    // 응답 본문 형식(fieldErrors)은 #96 머지 후 확인한다
    @ParameterizedTest
    @ValueSource(strings = {"Hong01", "hon", "abcdefghij0123456789x", "1hong", "hong_01", ""})
    @DisplayName("FNC-U-001 아이디 규칙을 어기면 400")
    void checkLoginId_invalid_badRequest(String loginId) throws Exception {
        mockMvc.perform(get(CHECK_ID_URL).param("loginId", loginId)).andExpect(status().isBadRequest());

        verifyNoInteractions(signupService);
    }

    @Test
    @DisplayName("FNC-U-001 loginId가 없으면 400")
    void checkLoginId_missing_badRequest() throws Exception {
        mockMvc.perform(get(CHECK_ID_URL)).andExpect(status().isBadRequest());

        verifyNoInteractions(signupService);
    }
}
