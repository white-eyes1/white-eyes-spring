/*
 * [해석본] 원본: user/src/test/java/com/whiteeyes/user/account/controller/SignupApiControllerTest.java (feature/29-check-login-id 브랜치)
 * 관련 이슈: #29(FNC-U-001), API: GET /api/v1/signup/check-id (public.md)
 *
 * [무엇] 컨트롤러가 요청을 제대로 받고, 규칙을 어긴 아이디를 서비스까지 보내지 않으며,
 *   400일 때 공통 오류 응답({code, message, fieldErrors}, conventions.md '오류 응답')이 나가는지 확인하는 테스트.
 * [어떻게] @SpringBootTest + @AutoConfigureMockMvc로 실제 앱과 같은 컨텍스트를 띄운다. 서비스만 @MockitoBean 가짜로 바꾼다.
 *   처음(#96 머지 전)에는 standaloneSetup이었는데, 400 본문 형식을 확인하려고 바꿨다. 이유는 아래 클래스 주석.
 * [실험으로 확인한 것] 컨트롤러에 @Validated를 잠깐 붙이고 이 테스트를 돌리면 규칙 위반 6건이 모두 400이 아니라 500이 된다
 *   (ConstraintViolationException → #96 핸들러의 '처리하지 못한 예외'). loginId 누락은 다른 예외라 400 그대로다.
 *   즉 '@Validated를 붙이지 않는다'는 컨트롤러 주석이 맞고, 누가 붙이면 이 테스트가 깨진다.
 */
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

// [해석] standaloneSetup은 컨트롤러 객체를 직접 올려서 (1) 앱에 등록된 GlobalExceptionHandler를 거치지 않고
//        (2) @Validated가 있을 때 생기는 AOP 프록시도 만들지 않는다. 그래서 '실제 앱에서 400 본문이 어떻게 나가는지'는 확인할 수 없다.
// [해석] @WebMvcTest를 쓰지 않은 이유: UserApplication에 @EnableJpaRepositories가 붙어 있어서
//        웹 계층만 띄우면 리포지토리를 만들다 실패한다. 테스트 DB는 H2라 전체 컨텍스트를 띄워도 된다.
// 앱과 같은 컨텍스트로 띄운다. 400 본문은 공통 예외 처리(FNC-C-027)가 만들고, 매개변수 검증 예외의 종류는
// 컨트롤러에 @Validated가 있는지에 따라 달라지므로 standalone 설정으로는 실제 응답을 확인할 수 없다
@SpringBootTest
@AutoConfigureMockMvc
class SignupApiControllerTest {

    private static final String CHECK_ID_URL = "/api/v1/signup/check-id";

    // [해석] 컨텍스트 안의 진짜 SignupService 빈을 가짜로 바꾼다. 그래서 DB 행 없이 true/false를 정할 수 있고,
    //        verifyNoInteractions로 '서비스를 부르지 않았다'도 볼 수 있다.
    @MockitoBean
    private SignupService signupService;

    @Autowired
    private MockMvc mockMvc;

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
    // [해석] 본문: code·message는 ErrorCode.INVALID_INPUT의 값, fieldErrors의 field는 @RequestParam("loginId")에 적은 이름,
    //        message는 @Pattern에 넣은 InputRules.LOGIN_ID_MESSAGE. 위반이 하나라 fieldErrors는 한 칸이다.
    // [해석] verifyNoInteractions: 형식이 틀리면 DB 조회(서비스 호출)까지 가지 않아야 한다(FNC-U-001 처리 순서 1 → 2).
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

    // [해석] 파라미터가 아예 없으면 @RequestParam의 required(기본 true) 때문에 MissingServletRequestParameterException.
    //        #96 핸들러가 이 예외를 field=파라미터 이름, message="필수입니다."로 바꾼다.
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
