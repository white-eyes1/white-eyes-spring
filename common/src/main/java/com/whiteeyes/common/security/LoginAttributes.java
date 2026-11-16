package com.whiteeyes.common.security;

/**
 * 로그인에 실패한 뒤 로그인 화면이 한 번 꺼내 쓰는 세션 속성 이름(FNC-C-001).
 * 명세의 실패 주소 /login?error를 그대로 지키려고 문구와 아이디를 주소가 아니라 세션에 둔다.
 */
public final class LoginAttributes {

    // 세션에 실패 문구를 담는 키
    public static final String ERROR_MESSAGE = "LOGIN_ERROR_MESSAGE";
    // 세션에 다시 채워 줄 아이디를 담는 키
    public static final String LOGIN_ID = "LOGIN_ID";

    // 상수만 모은 클래스라 객체를 만들지 못하게 막는다
    private LoginAttributes() {}
}
