package com.whiteeyes.user.account.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
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

/** 회원가입 API: 아이디 중복 확인(FNC-U-001), 회원가입(FNC-U-002). */
@RestController
@RequestMapping("/api/v1/signup")
@RequiredArgsConstructor
public class SignupApiController {

    private final SignupService signupService;

    /**
     * 아이디 중복 확인(FNC-U-001). 로그인 전에도 부를 수 있는 공개 API다(UserSecurityConfig의 /api/v1/signup/**).
     *
     * @param loginId 확인할 아이디. 형식이 틀리면 서비스까지 가지 않고 400으로 거절된다
     * @return available=true면 쓸 수 있는 아이디
     */
    // @Validated를 붙이지 않는다. Spring MVC 내장 메서드 검증이 HandlerMethodValidationException(400)을 낸다
    @GetMapping("/check-id")
    public LoginIdCheckResponse checkLoginId(
            @RequestParam("loginId") @Pattern(regexp = InputRules.LOGIN_ID_REGEX, message = InputRules.LOGIN_ID_MESSAGE)
                    String loginId) {
        // 서비스는 "쓸 수 있는가"를 돌려주고, 응답은 그 값을 JSON {"available": ...}으로 감싼다.
        return new LoginIdCheckResponse(signupService.isLoginIdAvailable(loginId));
    }

    /**
     * 회원가입(FNC-U-002). 입력 검증(@Valid)을 통과하면 PENDING 계정을 만들고 201 Created로 응답한다.
     *
     * @param request 가입 요청 본문(아이디·비밀번호·이름 등)
     * @return 만들어진 계정 번호와 상태(PENDING)
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SignupResponse signup(@Valid @RequestBody SignupRequest request) {
        // 실제 중복 검사·비밀번호 암호화·저장은 서비스가 한다. 컨트롤러는 요청을 받아 넘기기만 한다.
        return signupService.signup(request);
    }
}
