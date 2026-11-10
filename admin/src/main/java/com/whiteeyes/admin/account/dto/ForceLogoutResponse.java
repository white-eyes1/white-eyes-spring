package com.whiteeyes.admin.account.dto;

/**
 * 강제 로그아웃 응답(POST /api/v1/users/{id}/logout, /api/v1/admins/{id}/logout, FNC-A-008 정상 결과).
 *
 * @param deletedSessions 지운 세션 수. 세션이 없으면 0
 */
public record ForceLogoutResponse(int deletedSessions) {}
