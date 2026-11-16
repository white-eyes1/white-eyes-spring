package com.whiteeyes.common.account.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 내 정보 응답(GET·PATCH /api/v1/me, FNC-C-006). 관리자 앱 계정에만 role이 있고 사용자에는 칸 자체가 없다.
 *
 * @param status 사용자 ACTIVE·PENDING·WITHDRAWN, 관리자 ACTIVE·DISABLED
 * @param role 관리자 앱 계정의 ADMIN·SUPER_ADMIN. 사용자 계정이면 null이라 응답에서 빠진다
 */
// 각 칸은 AccountProfile에서 그대로 옮긴 값이다. 비밀번호 해시는 응답에 넣지 않는다
public record MyInfoResponse(
        Long id,
        String loginId,
        String name,
        String email,
        String phone,
        String status,
        boolean mustChangePassword,
        // role이 null이면(사용자 계정) JSON에서 칸 자체를 뺀다
        @JsonInclude(JsonInclude.Include.NON_NULL) String role) {}
