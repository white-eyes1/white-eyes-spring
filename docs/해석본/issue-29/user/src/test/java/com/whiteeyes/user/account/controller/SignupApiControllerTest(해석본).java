/*
 * [해석본] 원본: user/src/test/java/com/whiteeyes/user/account/controller/SignupApiControllerTest.java (29-fnc-u-001-아이디-중복-확인 브랜치)
 * 관련 이슈: #29(FNC-U-001), API: GET /api/v1/signup/check-id (public.md)
 *
 * [무엇] 컨트롤러가 요청을 제대로 받고, 규칙을 어긴 아이디를 서비스까지 보내지 않는지 확인하는 테스트.
 * [어떻게] 스프링 앱 전체를 띄우지 않는다. 서비스는 Mockito 가짜(@Mock)로 두고, MockMvc standaloneSetup으로 컨트롤러 하나만 올린다.
 *   #15의 CameraInternalControllerTest와 같은 방식이다. DB가 필요 없어서 빠르다.
 * [확인하지 않는 것] 400일 때의 응답 본문({code, message, fieldErrors}). 그 형식은 공통 오류 처리(#96)가 만들고,
 *   #96 머지 후 따로 테스트를 추가한다(이번 이슈 조건). 지금은 상태 400과 '서비스를 부르지 않았다'까지만 본다.
 */
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

    // [해석] standaloneSetup도 클래스패스에 Hibernate Validator가 있으면 @Pattern 검증을 그대로 돌린다.
    //        그래서 규칙 위반 테스트가 실제 앱과 같은 경로(HandlerMethodValidationException → 400)를 탄다.
    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new SignupApiController(signupService))
                .build();
    }

    // [해석] 응답 모양이 public.md의 {"available": true}와 똑같은지 본다.
    //        JsonCompareMode.STRICT: 다른 필드가 끼어 있어도 실패하게 한다(응답에 available 하나만 있어야 함).
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

    // [해석] 이미 있는 아이디는 오류(409 등)가 아니라 200 + false다. 409 DUPLICATE_LOGIN_ID는 가입 요청(FNC-U-002)의 몫이다.
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

    // [해석] 값마다 어기는 규칙이 하나씩 다르다:
    //        "Hong01" 대문자 / "hon" 3자(4자 미만) / "abcdefghij0123456789x" 21자(20자 초과) /
    //        "1hong" 숫자로 시작 / "hong_01" 허용하지 않는 문자(_) / "" 빈 값.
    // [해석] verifyNoInteractions: 형식이 틀리면 DB 조회(서비스 호출)까지 가지 않아야 한다(FNC-U-001 처리 순서 1 → 2).
    // 응답 본문 형식(fieldErrors)은 #96 머지 후 확인한다
    @ParameterizedTest
    @ValueSource(strings = {"Hong01", "hon", "abcdefghij0123456789x", "1hong", "hong_01", ""})
    @DisplayName("FNC-U-001 아이디 규칙을 어기면 400")
    void checkLoginId_invalid_badRequest(String loginId) throws Exception {
        mockMvc.perform(get(CHECK_ID_URL).param("loginId", loginId)).andExpect(status().isBadRequest());

        verifyNoInteractions(signupService);
    }

    // [해석] 파라미터가 아예 없으면 @RequestParam의 required(기본 true) 때문에 MissingServletRequestParameterException → 400.
    @Test
    @DisplayName("FNC-U-001 loginId가 없으면 400")
    void checkLoginId_missing_badRequest() throws Exception {
        mockMvc.perform(get(CHECK_ID_URL)).andExpect(status().isBadRequest());

        verifyNoInteractions(signupService);
    }
}
