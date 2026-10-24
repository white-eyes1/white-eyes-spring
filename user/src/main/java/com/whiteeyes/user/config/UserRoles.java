package com.whiteeyes.user.config;

/**
 * 사용자 앱의 역할 이름(FNC-C-001 처리 순서 4). PENDING·USER는 Spring Security 규칙(hasRole)에 쓰는 이름이라
 * "ROLE_" 접두사를 뺐고, 권한 목록과 직접 비교할 때는 PENDING_AUTHORITY를 쓴다.
 * 사용자 앱 로그인(UserAccountDetailsService)이 user_account.status가 PENDING이면 ROLE_PENDING, ACTIVE면 ROLE_USER를 줄 때 이 이름을 쓴다.
 * common의 LoginSuccessHandler가 승인 대기 화면으로 보낼 때 보는 "ROLE_PENDING"과 같은 값이다.
 */
public final class UserRoles {

    public static final String PENDING = "PENDING";
    public static final String USER = "USER";
    public static final String PENDING_AUTHORITY = "ROLE_" + PENDING;

    private UserRoles() {}
}
