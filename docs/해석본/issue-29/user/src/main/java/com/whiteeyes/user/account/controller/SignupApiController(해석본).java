/*
 * [해석본] 원본: user/src/main/java/com/whiteeyes/user/account/controller/SignupApiController.java (feature/29-check-login-id 브랜치)
 * 관련 이슈: #29(FNC-U-001), API: GET /api/v1/signup/check-id (public.md), 화면: SCR-U-001 '아이디 + 중복 확인'
 *
 * [무엇] 회원가입 화면이 부르는 REST API 입구.
 * [이름] REST API 컨트롤러는 'ApiController'(code-conventions 3-2). #30의 POST /api/v1/signup도 이 클래스에 들어갈 예정이라
 *   클래스 수준 경로를 /api/v1/signup으로 잡았다.
 * [하는 일] loginId 형식 검증 → 서비스 호출 → {available} 응답. DB 접근·판단은 하지 않는다(package-structure 3-1).
 * [권한] public.md에서 '공개'. 지금은 Spring Security 자체가 없어서 따로 열어 줄 설정이 없다.
 *   #4(FNC-C-003 접근 권한 검사)에서 보안 설정이 생기면 이 경로를 공개로 등록해야 한다.
 * [오류 응답] 규칙 위반 시 {code, message, fieldErrors} 본문은 공통 오류 처리(#96, dev에 머지됨)의 GlobalExceptionHandler가 만든다.
 *   이 컨트롤러에는 오류 처리 코드가 없다. 본문 형식은 SignupApiControllerTest가 확인한다.
 */
package com.whiteeyes.user.account.controller;

import jakarta.validation.constraints.Pattern;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.whiteeyes.common.constant.InputRules;
import com.whiteeyes.user.account.dto.LoginIdCheckResponse;
import com.whiteeyes.user.account.service.SignupService;

@RestController
@RequestMapping("/api/v1/signup")
public class SignupApiController {

    private final SignupService signupService;

    public SignupApiController(SignupService signupService) {
        this.signupService = signupService;
    }

    // [해석] @Validated를 붙이지 않은 이유: Spring 6.1부터 MVC가 @RequestParam에 붙은 제약(@Pattern)을 직접 검사하고,
    //        어기면 HandlerMethodValidationException(400)을 낸다. #96의 GlobalExceptionHandler는 바로 이 예외를 fieldErrors로 바꾼다.
    //        클래스에 @Validated를 붙이면 MVC 내장 검증 대신 AOP 방식 검증이 돌아 ConstraintViolationException이 나는데,
    //        #96은 그 예외를 따로 다루지 않아 500이 된다. 실제로 붙여서 SignupApiControllerTest를 돌려 보니
    //        규칙 위반 6건이 모두 500이었다(rebase 후 확인).
    // @Validated를 붙이지 않는다. Spring MVC 내장 메서드 검증이 HandlerMethodValidationException(400)을 낸다
    @GetMapping("/check-id")
    public LoginIdCheckResponse checkLoginId(
            // [해석] @RequestParam("loginId"): 이름을 직접 적는다. 이 프로젝트는 -parameters 옵션 없이 컴파일돼서
            //        이름을 생략하면 실행 중에 변수 이름을 알 수 없어 매칭이 실패한다(PR #96 '범위 밖/후속'에 적힌 규칙).
            //        필수 값(required 기본 true)이라 loginId가 아예 없으면 MissingServletRequestParameterException으로 400.
            // [해석] @Pattern: 아이디 규칙(4~20자, 영문 소문자로 시작, 영문 소문자·숫자만, FNC-U-001 처리 순서 1).
            //        빈 문자열("")도 정규식에 맞지 않아 400이다. 규칙과 문구는 common의 InputRules 한 곳에서 가져온다.
            @RequestParam("loginId") @Pattern(regexp = InputRules.LOGIN_ID_REGEX, message = InputRules.LOGIN_ID_MESSAGE)
                    String loginId) {
        // [해석] 정상이면 200 + {"available": true/false}. 이미 있는 아이디도 오류가 아니라 200에 false다(public.md).
        return new LoginIdCheckResponse(signupService.isLoginIdAvailable(loginId));
    }
}
