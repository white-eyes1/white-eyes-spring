/*
 * [해석본] 원본: common/src/main/java/com/whiteeyes/common/constant/InputRules.java (29-fnc-u-001-아이디-중복-확인 브랜치)
 * 관련 이슈: #29(FNC-U-001), 규칙: conventions.md '입력값 규칙 > 아이디(login_id)'
 *
 * [무엇] 입력값 규칙을 상수로 모은 곳. 지금은 아이디 규칙만 있다.
 * [왜 common/constant인가] 아이디 규칙은 이번 중복 확인뿐 아니라 #30 회원가입 요청 DTO에서도 똑같이 쓴다.
 *   한 곳에 두어야 규칙이 바뀌었을 때 두 곳이 어긋나지 않는다. package-structure 3장에 common/constant/ 자리가 있다.
 * [왜 상수인가] @Pattern(regexp = ..., message = ...) 같은 애너테이션 속성에는 컴파일 시점 상수만 넣을 수 있다.
 *   그래서 환경변수나 설정 파일이 아니라 static final String으로 둔다(운영 중 바꿀 값도 아님).
 * [아직 없는 것] 비밀번호·이름·메일·연락처 규칙. #30에서 필요할 때 여기에 추가한다.
 */
package com.whiteeyes.common.constant;

/**
 * 입력값 규칙(conventions.md '입력값 규칙'). 검증 애너테이션에서 쓰도록 컴파일 시점 상수로 둔다.
 */
public final class InputRules {

    // [해석] 정규식 읽는 법: ^ 시작, [a-z] 첫 글자는 영문 소문자, [a-z0-9]{3,19} 나머지 3~19자는 영문 소문자·숫자, $ 끝.
    //        첫 글자 1 + 나머지 3~19 = 전체 4~20자. 20자 상한은 user_account.login_id VARCHAR(20)과도 맞는다.
    //        빈 문자열·대문자·밑줄(_)·숫자로 시작하는 아이디는 모두 걸린다.
    /** 아이디: 4~20자, 영문 소문자로 시작, 영문 소문자·숫자만. */
    public static final String LOGIN_ID_REGEX = "^[a-z][a-z0-9]{3,19}$";

    // [해석] conventions.md·public.md·screens.md 어디에도 아이디 규칙 위반 문구는 정해져 있지 않다
    //        (오류 응답 예시의 '필수입니다.'와 SCR-U-001의 '규칙 위반 문구'라는 칸 이름뿐). 그래서 규칙을 그대로 문장으로 옮겼고,
    //        빈 값을 포함한 모든 위반에 이 문구 하나를 쓴다. 명세에 넣을지는 PR에서 검토를 요청한다.
    // 명세에 정해진 문구가 없어 규칙을 그대로 문장으로 옮겼다(#29)
    public static final String LOGIN_ID_MESSAGE = "아이디는 영문 소문자로 시작하는 4~20자의 영문 소문자·숫자여야 합니다.";

    // [해석] 상수만 담는 클래스라 객체를 만들 일이 없다. private 생성자 + final 클래스로 막는다.
    private InputRules() {}
}
