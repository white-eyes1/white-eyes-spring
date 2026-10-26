package com.whiteeyes.admin.account.service;

import com.whiteeyes.admin.account.dto.AdminListItem;
import com.whiteeyes.admin.account.dto.AdminSearchRequest;
import com.whiteeyes.admin.account.repository.AdminAccountQueryRepository;
import com.whiteeyes.admin.account.repository.AdminRow;
import com.whiteeyes.admin.account.repository.SessionActivityRepository.SessionTable;
import com.whiteeyes.common.web.PageResponse;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자 계정을 검색·필터·정렬해 페이지 단위로 돌려준다(FNC-A-003, 'FNC-A-002와 같은 방식').
 * 최근 활동은 관리자 앱 세션 표(SPRING_SESSION_ADMIN)로 계산한다. 최고 관리자만 부를 수 있다(권한 표 /api/v1/admins/**).
 */
@Service
@RequiredArgsConstructor
public class AdminListService {

    private final AdminAccountQueryRepository adminAccountQueryRepository;
    private final AccountActivityService accountActivityService;

    /** 사용자 목록(UserListService)과 같이, 세션 정보가 필요한 조건이면 상한까지 읽어 메모리에서 처리한다. 세션 조회는 한 번이다. */
    @Transactional(readOnly = true)
    public PageResponse<AdminListItem> list(AdminSearchRequest request) {
        if (request.isSessionDependent()) {
            return listInMemory(request);
        }
        List<AdminRow> rows = adminAccountQueryRepository.findPage(request);
        long totalElements = adminAccountQueryRepository.count(request);
        AccountActivities activities = accountActivityService.loadActivities(SessionTable.ADMIN, loginIdsOf(rows));
        List<AdminListItem> items = rows.stream().map(row -> toItem(row, activities)).toList();
        return PageResponse.of(items, request.page(), request.size(), totalElements);
    }

    private PageResponse<AdminListItem> listInMemory(AdminSearchRequest request) {
        int limit = UserListAssembler.SESSION_DEPENDENT_LIMIT;
        // 상한 + 1명까지 읽어 상한을 넘었는지 알 수 있게 한다
        List<AdminRow> candidates = adminAccountQueryRepository.findCandidates(request, limit + 1);
        boolean truncated = candidates.size() > limit;
        List<AdminRow> considered = truncated ? candidates.subList(0, limit) : candidates;
        AccountActivities activities = accountActivityService.loadActivities(SessionTable.ADMIN, loginIdsOf(considered));

        List<AdminListItem> matched = considered.stream()
                .map(row -> toItem(row, activities))
                .filter(item -> request.active() == null || item.active() == request.active())
                .sorted(comparatorFor(request))
                .toList();
        int from = (int) Math.min((long) request.page() * request.size(), matched.size());
        int to = Math.min(from + request.size(), matched.size());
        return PageResponse.of(matched.subList(from, to), request.page(), request.size(), matched.size(), truncated);
    }

    // 관리자 정보 수정(FNC-A-010)이 수정된 계정을 목록 한 줄 모양으로 돌려줄 때도 쓴다
    static AdminListItem toItem(AdminRow row, AccountActivities activities) {
        AccountActivity activity = activities.activityOf(row.loginId(), row.lastLoginAt());
        ZoneId zone = activities.zone();
        return new AdminListItem(
                row.id(),
                row.loginId(),
                row.name(),
                row.email(),
                row.phone(),
                row.role(),
                row.status(),
                toOffset(row.createdAt(), zone),
                toOffset(activity.lastActiveAt(), zone),
                activity.active());
    }

    private static OffsetDateTime toOffset(LocalDateTime dateTime, ZoneId zone) {
        return dateTime == null ? null : dateTime.atZone(zone).toOffsetDateTime();
    }

    // 사용자 목록과 같은 규칙: 같은 값이면 번호로 한 번 더, 최근 활동이 없는 계정은 방향과 관계없이 맨 뒤
    private static Comparator<AdminListItem> comparatorFor(AdminSearchRequest request) {
        boolean ascending = request.isAscending();
        Comparator<AdminListItem> primary = switch (request.sortField()) {
            case CREATED_AT -> by(AdminListItem::createdAt, ascending);
            case LOGIN_ID -> by(AdminListItem::loginId, ascending);
            case LAST_ACTIVE_AT -> by(AdminListItem::lastActiveAt, ascending);
        };
        return primary.thenComparing(by(AdminListItem::id, ascending));
    }

    private static <T extends Comparable<? super T>> Comparator<AdminListItem> by(
            Function<AdminListItem, T> key, boolean ascending) {
        Comparator<T> order = ascending ? Comparator.naturalOrder() : Comparator.reverseOrder();
        return Comparator.comparing(key, Comparator.nullsLast(order));
    }

    private static List<String> loginIdsOf(List<AdminRow> rows) {
        return rows.stream().map(AdminRow::loginId).toList();
    }
}
