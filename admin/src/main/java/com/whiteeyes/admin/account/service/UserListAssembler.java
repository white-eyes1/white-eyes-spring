package com.whiteeyes.admin.account.service;

import com.whiteeyes.admin.account.dto.UserListItem;
import com.whiteeyes.admin.account.dto.UserSearchRequest;
import com.whiteeyes.admin.account.repository.UserRow;
import com.whiteeyes.common.web.PageResponse;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

/**
 * 조회한 user_account 행에 최근 활동을 붙여 목록 응답을 만든다(FNC-A-002 처리 순서 2·3).
 * DB에서 페이지까지 자른 경우와, 세션 정보가 있어야 거르거나 정렬할 수 있어 메모리에서 처리하는 경우를 나눈다.
 */
public final class UserListAssembler {

    /** 세션 정보를 붙인 뒤 처리하는 목록의 최대 계정 수(FNC-A-002 업무 규칙). 넘으면 이만큼만 다룬다(#32에서 정함). */
    public static final int SESSION_DEPENDENT_LIMIT = 1000;

    private UserListAssembler() {}

    /**
     * DB에서 조건·정렬·페이지를 이미 적용한 한 페이지에 최근 활동을 붙인다.
     *
     * @param totalElements 조건에 맞는 전체 계정 수
     */
    public static PageResponse<UserListItem> assemblePage(
            List<UserRow> pageRows, long totalElements, UserActivities activities, UserSearchRequest request) {
        List<UserListItem> items = pageRows.stream()
                .map(row -> toItem(row, activities))
                .toList();
        return PageResponse.of(items, request.page(), request.size(), totalElements);
    }

    /**
     * DB 조건에 맞는 계정을 받아 메모리에서 active로 거르고, 정렬하고, 페이지를 자른다.
     * 후보가 상한보다 많으면 앞의 상한만큼만 다루고 truncated로 알린다. 그래서 호출하는 쪽은 상한 + 1개까지 조회해 넘긴다.
     *
     * @param candidates DB 조건(검색어·상태·가입일)에 맞는 계정, 상한 + 1개까지
     */
    public static PageResponse<UserListItem> assembleInMemory(
            List<UserRow> candidates, UserActivities activities, UserSearchRequest request) {
        boolean truncated = candidates.size() > SESSION_DEPENDENT_LIMIT;
        List<UserRow> limited = truncated ? candidates.subList(0, SESSION_DEPENDENT_LIMIT) : candidates;

        List<UserListItem> matched = limited.stream()
                .map(row -> toItem(row, activities))
                .filter(item -> request.active() == null || item.active() == request.active())
                .sorted(comparatorFor(request))
                .toList();

        // page에는 상한이 없어 int로 곱하면 넘칠 수 있다
        int from = (int) Math.min((long) request.page() * request.size(), matched.size());
        int to = Math.min(from + request.size(), matched.size());
        return PageResponse.of(matched.subList(from, to), request.page(), request.size(), matched.size(), truncated);
    }

    private static UserListItem toItem(UserRow row, UserActivities activities) {
        UserActivity activity = activities.activityOf(row.loginId(), row.lastLoginAt());
        ZoneId zone = activities.zone();
        return new UserListItem(
                row.id(),
                row.loginId(),
                row.name(),
                row.email(),
                row.phone(),
                row.status(),
                toOffset(row.createdAt(), zone),
                toOffset(activity.lastActiveAt(), zone),
                activity.active());
    }

    // DB에는 KST로 저장하고 API는 +09:00을 붙여 내보낸다(conventions.md '시각 형식')
    private static OffsetDateTime toOffset(LocalDateTime dateTime, ZoneId zone) {
        return dateTime == null ? null : dateTime.atZone(zone).toOffsetDateTime();
    }

    // 같은 값이면 번호로 한 번 더 정렬해 페이지를 넘겨도 순서가 흔들리지 않게 한다. 최근 활동이 없는 계정은 방향과 관계없이 맨 뒤(#32에서 정함)
    private static Comparator<UserListItem> comparatorFor(UserSearchRequest request) {
        boolean ascending = request.isAscending();
        Comparator<UserListItem> primary = switch (request.sortField()) {
            case CREATED_AT -> by(UserListItem::createdAt, ascending);
            case LOGIN_ID -> by(UserListItem::loginId, ascending);
            case LAST_ACTIVE_AT -> by(UserListItem::lastActiveAt, ascending);
        };
        return primary.thenComparing(by(UserListItem::id, ascending));
    }

    private static <T extends Comparable<? super T>> Comparator<UserListItem> by(
            Function<UserListItem, T> key, boolean ascending) {
        Comparator<T> order = ascending ? Comparator.naturalOrder() : Comparator.reverseOrder();
        return Comparator.comparing(key, Comparator.nullsLast(order));
    }
}
