package com.whiteeyes.admin.account.service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;

/**
 * 계정 여러 개의 세션 마지막 접근 시각을 한 번에 읽어 둔 것. 같은 기준 시각으로 활동 중 여부를 판정한다
 * (conventions.md '활동 중 판단').
 */
public class AccountActivities {

    // 로그인 아이디 -> 세션 마지막 접근 시각(세션이 없는 아이디는 키가 없다)
    private final Map<String, Instant> sessionLastAccessTimes;
    // 접근 시각(Instant)을 DB와 같은 로컬 시각으로 바꿀 때 쓰는 시간대
    private final ZoneId zone;
    // 이 시각 이후에 접근했으면 활동 중. '지금 - 활동 기준 시간'을 한 번만 계산해 목록 전체가 같은 기준을 쓴다
    private final LocalDateTime activeSince;

    // 같은 패키지의 AccountActivityService만 만든다(공개 생성자 없음)
    AccountActivities(Map<String, Instant> sessionLastAccessTimes, ZoneId zone, LocalDateTime activeSince) {
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
     * 활동 중은 세션이 있을 때만 그 마지막 접근 시각으로 판단한다. 마지막 로그인 시각은 표시만 하고 판단에 쓰지 않는다
     * (conventions.md '활동 중 판단'). 그래서 강제 로그아웃·비활성화로 세션이 지워지면 바로 활동 중이 아니다(#203).
     *
     * @param lastLoginAt user_account.last_login_at(없으면 null)
     */
    public AccountActivity activityOf(String loginId, LocalDateTime lastLoginAt) {
        Instant sessionLastAccess = sessionLastAccessTimes.get(loginId);
        // 세션이 없으면 마지막 로그인 시각만 보여 주고 활동 중은 아니다
        if (sessionLastAccess == null) {
            return new AccountActivity(lastLoginAt, false);
        }
        // 세션의 접근 시각을 DB 시각과 같은 시간대의 로컬 시각으로 맞춘다
        LocalDateTime lastActiveAt = LocalDateTime.ofInstant(sessionLastAccess, zone);
        // 기준 시각과 같거나 그 이후면 활동 중(경계 포함)
        return new AccountActivity(lastActiveAt, !lastActiveAt.isBefore(activeSince));
    }
}
