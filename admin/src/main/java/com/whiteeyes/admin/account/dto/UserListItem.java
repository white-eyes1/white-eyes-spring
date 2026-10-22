package com.whiteeyes.admin.account.dto;

import com.whiteeyes.common.account.entity.UserAccountStatus;
import java.time.OffsetDateTime;

/**
 * 사용자 목록 한 줄(GET /api/v1/users 응답 items, FNC-A-002 정상 결과).
 *
 * @param lastActiveAt 세션의 마지막 접근 시각, 세션이 없으면 마지막 로그인 시각. 둘 다 없으면 null
 * @param active 최근 활동이 활동 중 판단 기준 시간 안이면 true
 */
public record UserListItem(
        Long id,
        String loginId,
        String name,
        String email,
        String phone,
        UserAccountStatus status,
        OffsetDateTime createdAt,
        OffsetDateTime lastActiveAt,
        boolean active) {}
