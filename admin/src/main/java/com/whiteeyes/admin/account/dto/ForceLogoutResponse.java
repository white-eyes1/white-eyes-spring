package com.whiteeyes.admin.account.dto;

/**
 * 강제 로그아웃 응답(POST /api/v1/users/{id}/logout, /api/v1/admins/{id}/logout, FNC-A-008 정상 결과).
 *
 * @param deletedSessions 지운 세션 수. 세션이 없으면 0
 */
// 지운 세션이 없어도 정상 응답(0)이다. 화면은 이 수로 '몇 개 세션을 끊었는지' 알린다
public record ForceLogoutResponse(int deletedSessions) {}
