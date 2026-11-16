package com.whiteeyes.admin.account.entity;

/** 관리자 계정 상태(conventions.md '코드 값' admin_account.status). */
public enum AdminStatus {
    // 사용 중. 로그인할 수 있다
    ACTIVE,
    // 비활성화됨. 로그인할 수 없고 세션도 지워진다. 계정과 남긴 기록은 삭제하지 않는다
    DISABLED
}
