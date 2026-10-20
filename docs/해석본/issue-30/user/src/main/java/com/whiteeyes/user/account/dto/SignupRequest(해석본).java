/*
 * [해석본] 원본: user/src/main/java/com/whiteeyes/user/account/dto/SignupRequest.java (feature/30-signup 브랜치)
 * 관련 이슈: #30(FNC-U-002), API: POST /api/v1/signup (public.md), 화면: SCR-U-001
 *
 * [무엇] 가입 요청 본문. public.md 예시의 여섯 필드 그대로이고, 입력값 규칙(conventions.md)을 제약으로 붙였다.
 * [record] DTO는 record가 기본이다(code-conventions 3-4). Jackson이 생성자로 값을 넣는다.
 * [칸마다 오류 하나] 한 필드에 제약이 둘 이상이면 서로 겹치지 않게 골랐다. 그래서 칸 하나에 문구가 하나만 나온다.
 */
package com.whiteeyes.user.account.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import com.whiteeyes.common.constant.InputRules;

/** 회원가입 요청(POST /api/v1/signup). email·phone은 선택이라 비어 있어도 된다. */
// [해석] 클래스 제약. 필드 제약과 함께 한 번에 검사되므로 비밀번호 규칙 위반과 불일치가 같이 있으면 두 칸 모두 오류가 난다.
@PasswordConfirmMatch
public record SignupRequest(
        // [해석] @Pattern은 null을 맞는 것으로 본다(Bean Validation 규약). 그래서 값이 없으면 @NotNull이 '필수입니다.'로,
        //        빈 문자열·형식 위반은 @Pattern이 규칙 문구로 알린다. 둘이 동시에 걸리지 않는다. password도 같은 방식이다.
        @NotNull(message = InputRules.REQUIRED_MESSAGE)
                @Pattern(regexp = InputRules.LOGIN_ID_REGEX, message = InputRules.LOGIN_ID_MESSAGE)
                String loginId,
        @NotNull(message = InputRules.REQUIRED_MESSAGE)
                @Pattern(regexp = InputRules.PASSWORD_REGEX, message = InputRules.PASSWORD_MESSAGE)
                String password,
        // [해석] 확인 칸에는 규칙 검사를 하지 않는다. 비밀번호와 같으면 규칙도 같이 맞고, 다르면 불일치 오류 하나가 난다.
        @NotNull(message = InputRules.REQUIRED_MESSAGE) String passwordConfirm,
        // [해석] @NotBlank: null·빈 문자열·공백만 있는 값을 모두 '필수입니다.'로 막는다. '1~50자'의 1자 이상이 이것이다.
        @NotBlank(message = InputRules.REQUIRED_MESSAGE)
                @Size(max = InputRules.NAME_MAX_LENGTH, message = InputRules.NAME_MESSAGE)
                String name,
        // [해석] Hibernate Validator의 @Email은 null과 빈 문자열을 맞는 것으로 본다. 선택 항목이라 그대로 쓴다.
        //        형식 검사는 느슨하다('a@b'도 통과). 메일 발송 기능이 없어(tables.md) 더 엄격하게 하지 않았다.
        @Email(message = InputRules.EMAIL_MESSAGE)
                @Size(max = InputRules.EMAIL_MAX_LENGTH, message = InputRules.EMAIL_MESSAGE)
                String email,
        // [해석] null은 @Pattern이, ''는 정규식 {0,20}이 통과시킨다. 선택 항목이라 비워도 된다.
        @Pattern(regexp = InputRules.PHONE_REGEX, message = InputRules.PHONE_MESSAGE) String phone) {}
