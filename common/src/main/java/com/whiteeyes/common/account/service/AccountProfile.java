package com.whiteeyes.common.account.service;

/**
 * 내 정보(FNC-C-006)가 읽는 계정 한 건. 사용자(user_account)와 관리자(admin_account)를 같은 모양으로 다룬다.
 *
 * @param role 관리자 계정의 역할. 사용자 계정이면 null
 */
public record AccountProfile(
        Long id,
        String loginId,
        String name,
        String email,
        String phone,
        String status,
        String role,
        boolean mustChangePassword) {}
