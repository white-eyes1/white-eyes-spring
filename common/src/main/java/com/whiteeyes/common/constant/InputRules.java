package com.whiteeyes.common.constant;

/**
 * 입력값 규칙(conventions.md '입력값 규칙'). 검증 애너테이션에서 쓰도록 컴파일 시점 상수로 둔다.
 */
public final class InputRules {

    // 명세에 정해진 문구가 없어 규칙을 그대로 문장으로 옮겼다(#29, #30)
    public static final String REQUIRED_MESSAGE = "필수입니다.";

    /** 아이디: 4~20자, 영문 소문자로 시작, 영문 소문자·숫자만. */
    public static final String LOGIN_ID_REGEX = "^[a-z][a-z0-9]{3,19}$";

    public static final String LOGIN_ID_MESSAGE = "아이디는 영문 소문자로 시작하는 4~20자의 영문 소문자·숫자여야 합니다.";

    /** 비밀번호: 8~64자, 공백을 뺀 ASCII 출력 문자만, 영문과 숫자를 각각 1자 이상. */
    public static final String PASSWORD_REGEX = "^(?=.*[A-Za-z])(?=.*[0-9])[\\x21-\\x7E]{8,64}$";

    public static final String PASSWORD_MESSAGE = "비밀번호는 영문과 숫자를 각각 1자 이상 포함한 8~64자의 영문·숫자·특수문자여야 합니다.";

    public static final String PASSWORD_CONFIRM_MESSAGE = "비밀번호가 일치하지 않습니다.";

    public static final int NAME_MAX_LENGTH = 50;

    public static final String NAME_MESSAGE = "이름은 1~50자여야 합니다.";

    public static final int EMAIL_MAX_LENGTH = 100;

    public static final String EMAIL_MESSAGE = "메일은 100자 이하의 메일 형식이어야 합니다.";

    /** 연락처: 숫자와 - 만, 20자 이하. 선택 항목이라 빈 값도 맞는 것으로 본다. */
    public static final String PHONE_REGEX = "^[0-9-]{0,20}$";

    public static final String PHONE_MESSAGE = "연락처는 숫자와 -만 쓸 수 있고 20자 이하여야 합니다.";

    private InputRules() {}
}
