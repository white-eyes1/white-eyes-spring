package com.whiteeyes.common.constant;

/**
 * 입력값 규칙(conventions.md '입력값 규칙'). 검증 애너테이션에서 쓰도록 컴파일 시점 상수로 둔다.
 */
public final class InputRules {

    /** 아이디: 4~20자, 영문 소문자로 시작, 영문 소문자·숫자만. */
    public static final String LOGIN_ID_REGEX = "^[a-z][a-z0-9]{3,19}$";

    // 명세에 정해진 문구가 없어 규칙을 그대로 문장으로 옮겼다(#29)
    public static final String LOGIN_ID_MESSAGE = "아이디는 영문 소문자로 시작하는 4~20자의 영문 소문자·숫자여야 합니다.";

    private InputRules() {}
}
