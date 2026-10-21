package com.whiteeyes.common.security;

/**
 * 로그인에 실패한 뒤 로그인 화면이 한 번 꺼내 쓰는 세션 속성 이름(FNC-C-001).
 * 명세의 실패 주소 /login?error를 그대로 지키려고 문구와 아이디를 주소가 아니라 세션에 둔다.
 */
public final class LoginAttributes {

    public static final String ERROR_MESSAGE = "LOGIN_ERROR_MESSAGE";
    public static final String LOGIN_ID = "LOGIN_ID";

    private LoginAttributes() {}
}
