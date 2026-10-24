package com.whiteeyes.admin.account.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.whiteeyes.admin.account.config.ActiveWindowProperties;
import com.whiteeyes.admin.account.repository.SessionActivityRepository;
import com.whiteeyes.admin.account.repository.SessionActivityRepository.SessionTable;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 최근 활동 시각과 활동 중 판정(FNC-A-002). */
class AccountActivityServiceTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 21, 14, 0);

    private final SessionActivityRepository repository = mock(SessionActivityRepository.class);
    private final AccountActivityService service = new AccountActivityService(
            repository,
            new ActiveWindowProperties(Duration.ofMinutes(30)),
            Clock.fixed(NOW.atZone(KST).toInstant(), KST));

    @Test
    @DisplayName("FNC-A-002 세션이 있으면 세션의 마지막 접근 시각을 최근 활동으로 쓴다")
    void activityOf_sessionWins() {
        LocalDateTime sessionAccess = NOW.minusMinutes(5);
        AccountActivities activities = load("hong01", sessionAccess);

        AccountActivity activity = activities.activityOf("hong01", NOW.minusDays(3));

        assertThat(activity).isEqualTo(new AccountActivity(sessionAccess, true));
    }

    @Test
    @DisplayName("FNC-A-002 세션이 없으면 마지막 로그인 시각을 쓴다")
    void activityOf_noSession_usesLastLogin() {
        AccountActivities activities = load("hong01", NOW.minusMinutes(5));

        AccountActivity activity = activities.activityOf("kim02", NOW.minusMinutes(10));

        assertThat(activity).isEqualTo(new AccountActivity(NOW.minusMinutes(10), true));
    }

    @Test
    @DisplayName("FNC-A-002 세션도 로그인 기록도 없으면 최근 활동 없음, 활동 중 아님")
    void activityOf_nothing_inactive() {
        AccountActivities activities = load("hong01", NOW);

        assertThat(activities.activityOf("lee03", null)).isEqualTo(new AccountActivity(null, false));
    }

    @Test
    @DisplayName("FNC-A-002 최근 활동이 정확히 30분 전이면 활동 중, 그보다 이르면 아님")
    void activityOf_windowBoundary() {
        AccountActivities activities = load("hong01", NOW.minusMinutes(30));

        assertThat(activities.activityOf("hong01", null).active()).isTrue();
        assertThat(activities.activityOf("kim02", NOW.minusMinutes(30).minusSeconds(1)).active())
                .isFalse();
    }

    private AccountActivities load(String loginId, LocalDateTime sessionAccess) {
        List<String> loginIds = List.of("hong01", "kim02", "lee03");
        when(repository.findLastAccessTimes(SessionTable.USER, loginIds))
                .thenReturn(Map.of(loginId, sessionAccess.atZone(KST).toInstant()));
        return service.loadActivities(SessionTable.USER, loginIds);
    }
}
