/*
 * [해석본] 원본: user/src/test/java/com/whiteeyes/user/account/controller/SignupApiControllerTest.java (feature/30-signup 브랜치)
 * 관련 이슈: #29(FNC-U-001), #30(FNC-U-002)
 *
 * [무엇] 가입 API의 응답 상태·본문 형식을 확인하는 테스트. #30이 POST 테스트를 더했다.
 * [#29 해석본과의 관계] check-id 테스트와 @SpringBootTest로 띄우는 이유는 docs/해석본/issue-29/의 같은 파일에 있다.
 * [무엇을 보나] 201 본문, 409 본문, 규칙 위반 18가지의 400 fieldErrors(field 이름과 문구까지), 경계값 통과.
 *   서비스는 가짜(@MockitoBean)라 DB 저장·중복 판단은 SignupServiceTest와 동시성 테스트가 본다.
 */
package com.whiteeyes.user.account.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

import com.whiteeyes.common.account.entity.UserAccountStatus;
import com.whiteeyes.common.constant.InputRules;
import com.whiteeyes.user.account.dto.SignupRequest;
import com.whiteeyes.user.account.dto.SignupResponse;
import com.whiteeyes.user.account.exception.DuplicateLoginIdException;
import com.whiteeyes.user.account.service.SignupService;

// 앱과 같은 컨텍스트로 띄운다. 400 본문은 공통 예외 처리(FNC-C-027)가 만들고, 매개변수 검증 예외의 종류는
// 컨트롤러에 @Validated가 있는지에 따라 달라지므로 standalone 설정으로는 실제 응답을 확인할 수 없다
@SpringBootTest
@AutoConfigureMockMvc
class SignupApiControllerTest {

    private static final String CHECK_ID_URL = "/api/v1/signup/check-id";
    private static final String SIGNUP_URL = "/api/v1/signup";

    @MockitoBean
    private SignupService signupService;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    // [해석] JsonMapper: Boot 4의 Jackson 3(tools.jackson 패키지). 앱이 쓰는 매퍼로 요청 본문을 만든다.
    private JsonMapper jsonMapper;

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

    @Test
    // [해석] public.md 응답 예시와 같은 모양인지 STRICT로 본다(다른 필드가 끼면 실패).
    @DisplayName("FNC-U-002 가입하면 201 + id, status=PENDING")
    void signup_valid_created() throws Exception {
        given(signupService.signup(any(SignupRequest.class)))
                .willReturn(new SignupResponse(21L, UserAccountStatus.PENDING));

        mockMvc.perform(signupRequest(validBody()))
                .andExpect(status().isCreated())
                .andExpect(content().json("""
                        {"id": 21, "status": "PENDING"}
                        """, JsonCompareMode.STRICT));
    }

    @ParameterizedTest
    @MethodSource("validBoundaryBodies")
    // [해석] 규칙의 끝값은 통과해야 한다: 비밀번호 8자·64자(특수문자 포함), 이름 50자, 메일 100자, 연락처 20자,
    //        선택 항목 null·''. 끝값 바로 바깥은 아래 400 테스트에 있다.
    @DisplayName("FNC-U-002 입력값 규칙의 경계값과 선택 항목 생략은 통과")
    void signup_boundary_created(Map<String, Object> body) throws Exception {
        given(signupService.signup(any(SignupRequest.class)))
                .willReturn(new SignupResponse(21L, UserAccountStatus.PENDING));

        mockMvc.perform(signupRequest(body)).andExpect(status().isCreated());

        verify(signupService).signup(any(SignupRequest.class));
    }

    @Test
    // [해석] 서비스가 던진 예외가 #96 핸들러를 거쳐 public.md의 409 DUPLICATE_LOGIN_ID가 되는지. fieldErrors는 없어야 한다.
    @DisplayName("FNC-U-002 아이디가 중복이면 409 DUPLICATE_LOGIN_ID")
    void signup_duplicate_conflict() throws Exception {
        given(signupService.signup(any(SignupRequest.class))).willThrow(new DuplicateLoginIdException());

        mockMvc.perform(signupRequest(validBody()))
                .andExpect(status().isConflict())
                .andExpect(content().json("""
                        {"code": "DUPLICATE_LOGIN_ID", "message": "이미 사용 중인 아이디입니다."}
                        """, JsonCompareMode.STRICT));
    }

    // [해석] 경우마다 오류가 정확히 하나가 되게 만들었다(STRICT라 fieldErrors 배열이 한 칸이어야 함).
    //        여러 칸이 동시에 틀리면 배열 순서가 정해져 있지 않아 비교가 흔들린다. name = "{0}": 결과에 경우 이름이 보인다.
    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidBodies")
    @DisplayName("FNC-U-002 입력값 규칙을 어기면 400 + 해당 칸 오류")
    void signup_invalid_badRequest(String description, Map<String, Object> body, String field, String message)
            throws Exception {
        mockMvc.perform(signupRequest(body))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("""
                        {"code": "INVALID_INPUT",
                         "message": "입력값을 확인해 주세요.",
                         "fieldErrors": [{"field": "%s", "message": "%s"}]}
                        """.formatted(field, message), JsonCompareMode.STRICT));

        verifyNoInteractions(signupService);
    }

    static Stream<Map<String, Object>> validBoundaryBodies() {
        String password64 = "a1" + "!".repeat(62);
        return Stream.of(
                with(validBody(), "password", "abcd1234", "passwordConfirm", "abcd1234"),
                with(validBody(), "password", password64, "passwordConfirm", password64),
                with(validBody(), "name", "가".repeat(50)),
                with(validBody(), "email", "a".repeat(60) + "@" + "b".repeat(35) + ".com"),
                with(validBody(), "phone", "0".repeat(20)),
                with(validBody(), "email", null, "phone", null),
                with(validBody(), "email", "", "phone", ""));
    }

    static Stream<Arguments> invalidBodies() {
        return Stream.of(
                invalid("아이디 없음", with(validBody(), "loginId", null), "loginId", InputRules.REQUIRED_MESSAGE),
                invalid("아이디 대문자", with(validBody(), "loginId", "Hong01"), "loginId", InputRules.LOGIN_ID_MESSAGE),
                invalid("비밀번호 없음", with(validBody(), "password", null), "password", InputRules.REQUIRED_MESSAGE),
                invalidPassword("비밀번호 7자", "abcd123"),
                invalidPassword("비밀번호 65자", "a1" + "!".repeat(63)),
                invalidPassword("비밀번호 숫자 없음", "abcdefgh"),
                invalidPassword("비밀번호 영문 없음", "12345678"),
                invalidPassword("비밀번호 공백 포함", "abcd 1234"),
                invalidPassword("비밀번호 한글 포함", "abcd1234가"),
                invalid("비밀번호 확인 없음", with(validBody(), "passwordConfirm", null),
                        "passwordConfirm", InputRules.REQUIRED_MESSAGE),
                // [해석] 불일치 오류의 field가 화면 칸 이름과 같은 passwordConfirm인지 여기서 확인한다(PasswordConfirmMatchValidator).
                invalid("비밀번호 확인 불일치", with(validBody(), "passwordConfirm", "abcd12345"),
                        "passwordConfirm", InputRules.PASSWORD_CONFIRM_MESSAGE),
                invalid("이름 없음", with(validBody(), "name", null), "name", InputRules.REQUIRED_MESSAGE),
                invalid("이름 공백만", with(validBody(), "name", "  "), "name", InputRules.REQUIRED_MESSAGE),
                invalid("이름 51자", with(validBody(), "name", "가".repeat(51)), "name", InputRules.NAME_MESSAGE),
                invalid("메일 형식 아님", with(validBody(), "email", "not-an-email"), "email", InputRules.EMAIL_MESSAGE),
                invalid("메일 101자", with(validBody(), "email", "a".repeat(61) + "@" + "b".repeat(35) + ".com"),
                        "email", InputRules.EMAIL_MESSAGE),
                invalid("연락처 문자 포함", with(validBody(), "phone", "010-1234-567a"), "phone", InputRules.PHONE_MESSAGE),
                invalid("연락처 21자", with(validBody(), "phone", "0".repeat(21)), "phone", InputRules.PHONE_MESSAGE));
    }

    // public.md의 요청 예시
    private static Map<String, Object> validBody() {
        Map<String, Object> body = new HashMap<>();
        body.put("loginId", "hong01");
        body.put("password", "abcd1234");
        body.put("passwordConfirm", "abcd1234");
        body.put("name", "홍길동");
        body.put("email", "a@b.com");
        body.put("phone", "010-1234-5678");
        return body;
    }

    // 키와 값을 번갈아 받아 덮어쓴다. null을 넣으려고 Map.of 대신 HashMap을 쓴다
    private static Map<String, Object> with(Map<String, Object> body, Object... keyValues) {
        for (int i = 0; i < keyValues.length; i += 2) {
            body.put((String) keyValues[i], keyValues[i + 1]);
        }
        return body;
    }

    private static Arguments invalid(String description, Map<String, Object> body, String field, String message) {
        return Arguments.of(description, body, field, message);
    }

    // 비밀번호 확인도 같은 값으로 바꿔 비밀번호 규칙 오류 하나만 나게 한다
    // [해석] 비밀번호만 바꾸면 확인 칸과 달라져 불일치 오류도 함께 난다. 그래서 확인 칸도 같은 값으로 바꾼다.
    private static Arguments invalidPassword(String description, String password) {
        return invalid(description, with(validBody(), "password", password, "passwordConfirm", password),
                "password", InputRules.PASSWORD_MESSAGE);
    }

    private MockHttpServletRequestBuilder signupRequest(Map<String, Object> body) {
        return post(SIGNUP_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonMapper.writeValueAsString(body));
    }
}
