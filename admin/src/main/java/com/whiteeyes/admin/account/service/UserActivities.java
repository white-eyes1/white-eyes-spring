package com.whiteeyes.admin.account.service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;

/**
 * 계정 여러 개의 세션 마지막 접근 시각을 한 번에 읽어 둔 것. 같은 기준 시각으로 활동 중 여부를 판정한다
 * (conventions.md '활동 중 판단').
 */
public class UserActivities {

    private final Map<String, Instant> sessionLastAccessTimes;
    private final ZoneId zone;
    private final LocalDateTime activeSince;

    UserActivities(Map<String, Instant> sessionLastAccessTimes, ZoneId zone, LocalDateTime activeSince) {
        this.sessionLastAccessTimes = sessionLastAccessTimes;
        this.zone = zone;
        this.activeSince = activeSince;
    }

    /** DB 시각(KST)을 응답 시각으로 바꿀 때 쓰는 시간대. */
    public ZoneId zone() {
        return zone;
    }

    /**
     * 최근 활동 시각은 세션의 마지막 접근 시각이고, 세션이 없으면 마지막 로그인 시각이다(aclst-004).
     *
     * @param lastLoginAt user_account.last_login_at(없으면 null)
     */
    public UserActivity activityOf(String loginId, LocalDateTime lastLoginAt) {
        Instant sessionLastAccess = sessionLastAccessTimes.get(loginId);
        LocalDateTime lastActiveAt =
                sessionLastAccess == null ? lastLoginAt : LocalDateTime.ofInstant(sessionLastAccess, zone);
        boolean active = lastActiveAt != null && !lastActiveAt.isBefore(activeSince);
        return new UserActivity(lastActiveAt, active);
    }
}
