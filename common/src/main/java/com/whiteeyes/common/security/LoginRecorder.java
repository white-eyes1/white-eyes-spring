package com.whiteeyes.common.security;

/**
 * 로그인 성공 시 그 앱의 계정 테이블에 마지막 로그인 시각을 남긴다(FNC-C-001).
 * 계정 테이블은 앱마다 달라서(user_account / admin_account) 앱이 구현한다.
 */
@FunctionalInterface
public interface LoginRecorder {

    void record(String loginId);
}
