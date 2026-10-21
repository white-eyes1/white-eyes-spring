package com.whiteeyes.admin.account.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.whiteeyes.admin.account.dto.UserListItem;
import com.whiteeyes.admin.account.dto.UserSearchRequest;
import com.whiteeyes.admin.account.repository.UserRow;
import com.whiteeyes.common.account.entity.UserAccountStatus;
import com.whiteeyes.common.web.PageResponse;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class UserListAssemblerTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 21, 14, 0);
    private static final LocalDateTime JOINED = LocalDateTime.of(2026, 10, 1, 9, 0);

    private final Map<String, Instant> sessions = new HashMap<>();

    @Test
    @DisplayName("FNC-A-002 DB 페이지에 최근 활동을 붙이고, 시각은 +09:00으로 내보낸다")
    void assemblePage_attachesActivity_andOffset() {
        sessions.put("hong01", toInstant(NOW.minusMinutes(5)));
        List<UserRow> rows = List.of(row(1, "hong01", null), row(2, "kim02", NOW.minusDays(2)));

        PageResponse<UserListItem> page =
                UserListAssembler.assemblePage(rows, 45, activities(), request(null, "createdAt,desc", 1, 20));

        assertThat(page.page()).isEqualTo(1);
        assertThat(page.size()).isEqualTo(20);
        assertThat(page.totalElements()).isEqualTo(45);
        assertThat(page.totalPages()).isEqualTo(3);
        assertThat(page.truncated()).isFalse();

        UserListItem hong = page.items().get(0);
        assertThat(hong.createdAt()).isEqualTo(OffsetDateTime.of(JOINED, ZoneOffset.ofHours(9)));
        assertThat(hong.lastActiveAt()).isEqualTo(OffsetDateTime.of(NOW.minusMinutes(5), ZoneOffset.ofHours(9)));
        assertThat(hong.active()).isTrue();

        UserListItem kim = page.items().get(1);
        assertThat(kim.lastActiveAt()).isEqualTo(OffsetDateTime.of(NOW.minusDays(2), ZoneOffset.ofHours(9)));
        assertThat(kim.active()).isFalse();
    }

    @Test
    @DisplayName("FNC-A-002 후보가 1,001명이면 1,000명까지만 다루고 truncated")
    void assembleInMemory_overLimit_truncated() {
        PageResponse<UserListItem> page =
                UserListAssembler.assembleInMemory(rows(1001), activities(), request(null, "lastActiveAt", 0, 20));

        assertThat(page.truncated()).isTrue();
        assertThat(page.totalElements()).isEqualTo(1000);
        assertThat(page.totalPages()).isEqualTo(50);
    }

    @Test
    @DisplayName("FNC-A-002 후보가 정확히 1,000명이면 truncated가 아니다")
    void assembleInMemory_atLimit_notTruncated() {
        PageResponse<UserListItem> page =
                UserListAssembler.assembleInMemory(rows(1000), activities(), request(null, "lastActiveAt", 0, 20));

        assertThat(page.truncated()).isFalse();
        assertThat(page.totalElements()).isEqualTo(1000);
    }

    @Test
    @DisplayName("FNC-A-002 active로 거르면 전체 개수도 거른 뒤 개수")
    void assembleInMemory_activeFilter() {
        sessions.put("u001", toInstant(NOW.minusMinutes(1)));
        List<UserRow> rows = List.of(
                row(1, "u001", null), row(2, "u002", NOW.minusMinutes(10)), row(3, "u003", NOW.minusHours(2)));

        PageResponse<UserListItem> active =
                UserListAssembler.assembleInMemory(rows, activities(), request(true, "loginId,asc", 0, 20));
        PageResponse<UserListItem> inactive =
                UserListAssembler.assembleInMemory(rows, activities(), request(false, "loginId,asc", 0, 20));

        assertThat(active.items()).extracting(UserListItem::loginId).containsExactly("u001", "u002");
        assertThat(active.totalElements()).isEqualTo(2);
        assertThat(inactive.items()).extracting(UserListItem::loginId).containsExactly("u003");
        assertThat(inactive.totalElements()).isEqualTo(1);
    }

    @Test
    @DisplayName("FNC-A-002 최근 활동 정렬: 오름·내림 모두 최근 활동이 없는 계정은 맨 뒤")
    void assembleInMemory_lastActiveAtSort_nullsLast() {
        List<UserRow> rows = List.of(
                row(1, "u001", null),
                row(2, "u002", NOW.minusHours(3)),
                row(3, "u003", NOW.minusHours(1)),
                row(4, "u004", NOW.minusHours(2)));

        PageResponse<UserListItem> asc =
                UserListAssembler.assembleInMemory(rows, activities(), request(null, "lastActiveAt,asc", 0, 20));
        PageResponse<UserListItem> desc =
                UserListAssembler.assembleInMemory(rows, activities(), request(null, "lastActiveAt,desc", 0, 20));

        assertThat(asc.items()).extracting(UserListItem::loginId).containsExactly("u002", "u004", "u003", "u001");
        assertThat(desc.items()).extracting(UserListItem::loginId).containsExactly("u003", "u004", "u002", "u001");
    }

    @Test
    @DisplayName("FNC-A-002 정렬 값이 같으면 번호로 한 번 더 정렬한다")
    void assembleInMemory_tieBreakById() {
        List<UserRow> rows = List.of(row(3, "u003", null), row(1, "u001", null), row(2, "u002", null));

        PageResponse<UserListItem> asc =
                UserListAssembler.assembleInMemory(rows, activities(), request(false, "createdAt,asc", 0, 20));
        PageResponse<UserListItem> desc =
                UserListAssembler.assembleInMemory(rows, activities(), request(false, "createdAt,desc", 0, 20));

        assertThat(asc.items()).extracting(UserListItem::id).containsExactly(1L, 2L, 3L);
        assertThat(desc.items()).extracting(UserListItem::id).containsExactly(3L, 2L, 1L);
    }

    @Test
    @DisplayName("FNC-A-002 페이지 이동: 마지막 페이지는 남은 만큼, 범위 밖 페이지는 빈 목록")
    void assembleInMemory_paging() {
        List<UserRow> rows = rows(45);

        PageResponse<UserListItem> last =
                UserListAssembler.assembleInMemory(rows, activities(), request(false, "loginId,asc", 2, 20));
        PageResponse<UserListItem> beyond =
                UserListAssembler.assembleInMemory(rows, activities(), request(false, "loginId,asc", 5, 20));

        assertThat(last.items()).extracting(UserListItem::loginId).first().isEqualTo("u0041");
        assertThat(last.items()).hasSize(5);
        assertThat(last.totalPages()).isEqualTo(3);
        assertThat(beyond.items()).isEmpty();
        assertThat(beyond.totalElements()).isEqualTo(45);
    }

    @Test
    @DisplayName("FNC-A-002 page가 아주 커서 page×size가 int를 넘어도 빈 목록")
    void assembleInMemory_hugePage_empty() {
        PageResponse<UserListItem> page = UserListAssembler.assembleInMemory(
                rows(3), activities(), request(false, "loginId,asc", Integer.MAX_VALUE, 100));

        assertThat(page.items()).isEmpty();
        assertThat(page.totalElements()).isEqualTo(3);
    }

    private UserActivities activities() {
        return new UserActivities(sessions, KST, NOW.minusMinutes(30));
    }

    private static UserSearchRequest request(Boolean active, String sort, int page, int size) {
        return new UserSearchRequest(null, null, null, null, active, sort, page, size);
    }

    private static UserRow row(long id, String loginId, LocalDateTime lastLoginAt) {
        return new UserRow(id, loginId, "이름" + id, null, null, UserAccountStatus.ACTIVE, JOINED, lastLoginAt);
    }

    private static List<UserRow> rows(int count) {
        return IntStream.rangeClosed(1, count)
                .mapToObj(i -> row(i, "u%04d".formatted(i), null))
                .toList();
    }

    private static Instant toInstant(LocalDateTime dateTime) {
        return dateTime.atZone(KST).toInstant();
    }
}
