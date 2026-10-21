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

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SignupResponse signup(@Valid @RequestBody SignupRequest request) {
        return signupService.signup(request);
    }
}
