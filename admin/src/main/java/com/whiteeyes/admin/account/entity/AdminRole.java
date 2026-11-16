package com.whiteeyes.admin.account.entity;

/** 관리자 권한 등급(conventions.md '코드 값' admin_account.role). */
public enum AdminRole {
    // 일반 관리자. 사용자 관리·승인 등을 할 수 있고, 관리자 계정 관리는 할 수 없다
    ADMIN,
    // 최고 관리자. 관리자 계정 생성·수정·비활성화 등 관리자 관리까지 할 수 있다(앱 시작 때 환경변수로 한 번 등록)
    SUPER_ADMIN
}
