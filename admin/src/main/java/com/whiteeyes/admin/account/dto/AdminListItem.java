package com.whiteeyes.admin.account.dto;

import com.whiteeyes.admin.account.entity.AdminRole;
import com.whiteeyes.admin.account.entity.AdminStatus;
import java.time.OffsetDateTime;

/**
 * 관리자 목록 한 줄(GET /api/v1/admins 응답 items, FNC-A-003). 사용자 목록 한 줄과 같은 형식에 role을 더한다(api/public.md).
 *
 * @param lastActiveAt 관리자 앱 세션의 마지막 접근 시각, 세션이 없으면 마지막 로그인 시각. 둘 다 없으면 null
 * @param active 최근 활동이 활동 중 판단 기준 시간 안이면 true
 */
public record AdminListItem(
        Long id,
        String loginId,
        String name,
        String email,
        String phone,
        AdminRole role,
        AdminStatus status,
        OffsetDateTime createdAt,
        OffsetDateTime lastActiveAt,
        boolean active) {}
