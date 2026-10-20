/*
 * [해석본] 원본: user/src/main/java/com/whiteeyes/user/account/controller/SignupApiController.java (feature/30-signup 브랜치)
 * 관련 이슈: #29(FNC-U-001), #30(FNC-U-002), API: GET /api/v1/signup/check-id, POST /api/v1/signup (public.md)
 *
 * [무엇] 회원가입 화면이 부르는 REST API 입구. #30이 가입 요청(POST)을 더했다.
 * [#29 해석본과의 관계] check-id와 @Validated를 붙이지 않는 이유는 docs/해석본/issue-29/의 같은 파일에 설명이 있다.
 * [하는 일] 요청 본문 검증 → 서비스 호출 → 201 응답. 중복 판단·저장은 서비스가 한다(package-structure 3-1).
 */
package com.whiteeyes.user.account.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.whiteeyes.common.constant.InputRules;
import com.whiteeyes.user.account.dto.LoginIdCheckResponse;
import com.whiteeyes.user.account.dto.SignupRequest;
import com.whiteeyes.user.account.dto.SignupResponse;
import com.whiteeyes.user.account.service.SignupService;

@RestController
@RequestMapping("/api/v1/signup")
public class SignupApiController {

    private final SignupService signupService;

    public SignupApiController(SignupService signupService) {
        this.signupService = signupService;
    }

    // @Validated를 붙이지 않는다. Spring MVC 내장 메서드 검증이 HandlerMethodValidationException(400)을 낸다
    @GetMapping("/check-id")
    public LoginIdCheckResponse checkLoginId(
            @RequestParam("loginId") @Pattern(regexp = InputRules.LOGIN_ID_REGEX, message = InputRules.LOGIN_ID_MESSAGE)
                    String loginId) {
        return new LoginIdCheckResponse(signupService.isLoginIdAvailable(loginId));
    }

    // [해석] 클래스 경로 /api/v1/signup 그대로 POST(public.md). @ResponseStatus(CREATED): 성공 응답은 201이다.
    //        409는 이 메서드에서 다루지 않는다. 서비스가 던진 DuplicateLoginIdException을 #96 핸들러가 409로 바꾼다.
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    // [해석] @Valid: 본문(SignupRequest)의 제약을 검사한다. 실패하면 MethodArgumentNotValidException(BindException의 하위)이
    //        나고, #96 핸들러의 handleBind가 400 fieldErrors로 바꾼다. check-id와 예외 종류는 다르지만 응답 형식은 같다.
    //        이 메서드는 매개변수 자체에 제약(@Pattern 등)이 없어서 HandlerMethodValidationException이 아니라 이 예외가 난다.
    public SignupResponse signup(@Valid @RequestBody SignupRequest request) {
        return signupService.signup(request);
    }
}
