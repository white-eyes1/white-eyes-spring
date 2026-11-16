package com.whiteeyes.common.account.entity;

/** 사용자 계정 상태(tables.md user_account.status). */
public enum UserAccountStatus {
    // 가입 후 관리자 승인 대기. 로그인할 수 없다
    PENDING,
    // 승인 완료. 정상 이용
    ACTIVE,
    // 탈퇴. 행은 남고 같은 아이디로 재가입할 수 없다
    WITHDRAWN
}
