package com.whiteeyes.user.account.controller;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;

import com.whiteeyes.common.constant.InputRules;
import com.whiteeyes.user.account.service.SignupService;

// 앱과 같은 컨텍스트로 띄운다. 400 본문은 공통 예외 처리(FNC-C-027)가 만들고, 매개변수 검증 예외의 종류는
// 컨트롤러에 @Validated가 있는지에 따라 달라지므로 standalone 설정으로는 실제 응답을 확인할 수 없다
@SpringBootTest
@AutoConfigureMockMvc
class SignupApiControllerTest {

    private static final String CHECK_ID_URL = "/api/v1/signup/check-id";

    @MockitoBean
    private SignupService signupService;

    @Autowired
    private MockMvc mockMvc;

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

    @ParameterizedTest
    @ValueSource(strings = {"Hong01", "hon", "abcdefghij0123456789x", "1hong", "hong_01", ""})
    @DisplayName("FNC-U-001 아이디 규칙을 어기면 400 + loginId 칸 오류")
    void checkLoginId_invalid_badRequest(String loginId) throws Exception {
        mockMvc.perform(get(CHECK_ID_URL).param("loginId", loginId))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("""
                        {"code": "INVALID_INPUT",
                         "message": "입력값을 확인해 주세요.",
                         "fieldErrors": [{"field": "loginId", "message": "%s"}]}
                        """.formatted(InputRules.LOGIN_ID_MESSAGE), JsonCompareMode.STRICT));

        verifyNoInteractions(signupService);
    }

    @Test
    @DisplayName("FNC-U-001 loginId가 없으면 400 + loginId 칸 오류")
    void checkLoginId_missing_badRequest() throws Exception {
        mockMvc.perform(get(CHECK_ID_URL))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("""
                        {"code": "INVALID_INPUT",
                         "message": "입력값을 확인해 주세요.",
                         "fieldErrors": [{"field": "loginId", "message": "필수입니다."}]}
                        """, JsonCompareMode.STRICT));

        verifyNoInteractions(signupService);
    }
}
