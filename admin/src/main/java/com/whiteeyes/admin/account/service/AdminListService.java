package com.whiteeyes.admin.account.service;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.whiteeyes.admin.account.dto.AdminListItem;
import com.whiteeyes.admin.account.dto.AdminSearchRequest;
import com.whiteeyes.admin.account.repository.AdminAccountQueryRepository;
import com.whiteeyes.admin.account.repository.AdminRow;
import com.whiteeyes.admin.account.repository.SessionActivityRepository.SessionTable;
import com.whiteeyes.common.web.PageResponse;

/**
 * 관리자 계정을 검색·필터·정렬해 페이지 단위로 돌려준다(FNC-A-003, 'FNC-A-002와 같은 방식').
 * 최근 활동은 관리자 앱 세션 표(SPRING_SESSION_ADMIN)로 계산한다. 최고 관리자만 부를 수 있다(권한 표 /api/v1/admins/**).
 */
@Service
@RequiredArgsConstructor
public class AdminListService {

    // 관리자 목록을 읽는 Criteria 저장소
    private final AdminAccountQueryRepository adminAccountQueryRepository;
    // 세션 표로 최근 활동·활동 중 여부를 계산한다
    private final AccountActivityService accountActivityService;

    /** 사용자 목록(UserListService)과 같이, 세션 정보가 필요한 조건이면 상한까지 읽어 메모리에서 처리한다. 세션 조회는 한 번이다. */
    @Transactional(readOnly = true)
    public PageResponse<AdminListItem> list(AdminSearchRequest request) {
        // 활동 중 필터·최근 활동 정렬은 세션 정보가 있어야 하므로 메모리에서 처리하는 경로로 보낸다
        if (request.isSessionDependent()) {
            return listInMemory(request);
        }
        // 세션이 필요 없는 조건: 거르기·정렬·페이지 자르기를 DB가 처리한다
        List<AdminRow> rows = adminAccountQueryRepository.findPage(request);
        // 페이지 바깥을 포함한 전체 개수(화면의 페이지 수 계산용)
        long totalElements = adminAccountQueryRepository.count(request);
        // 이 페이지 계정들의 세션을 한 번에 읽는다
        AccountActivities activities = accountActivityService.loadActivities(SessionTable.ADMIN, loginIdsOf(rows));
        List<AdminListItem> items =
                rows.stream().map(row -> toItem(row, activities)).toList();
        return PageResponse.of(items, request.page(), request.size(), totalElements);
    }

    /**
     * 세션 정보가 있어야 하는 조건(활동 중 필터·최근 활동 정렬)을 처리한다. DB 조건에 맞는 계정을 상한까지 읽어
     * 세션으로 활동 여부를 계산한 뒤 메모리에서 거르고 정렬하고 페이지를 자른다.
     */
    /**
     * 세션 정보가 있어야 하는 조건(활동 중 필터·최근 활동 정렬)을 처리한다. DB 조건에 맞는 계정을 상한까지 읽어
     * 세션으로 활동 여부를 계산한 뒤 메모리에서 거르고 정렬하고 페이지를 자른다.
     */
    private PageResponse<AdminListItem> listInMemory(AdminSearchRequest request) {
        // 메모리에서 다룰 최대 계정 수(사용자 목록과 같은 상한)
        int limit = UserListAssembler.SESSION_DEPENDENT_LIMIT;
        // 상한 + 1명까지 읽어 상한을 넘었는지 알 수 있게 한다
        List<AdminRow> candidates = adminAccountQueryRepository.findCandidates(request, limit + 1);
        // 상한+1명이 읽혔다면 상한을 넘는 계정이 더 있다는 뜻이다
        boolean truncated = candidates.size() > limit;
        // 실제로 다루는 것은 상한까지만이다
        List<AdminRow> considered = truncated ? candidates.subList(0, limit) : candidates;
        AccountActivities activities =
                accountActivityService.loadActivities(SessionTable.ADMIN, loginIdsOf(considered));

        // 세션 정보로 화면 항목을 만든 뒤 활동 여부로 거르고 정렬한다
        List<AdminListItem> matched = considered.stream()
                .map(row -> toItem(row, activities))
                // active 조건을 보내지 않았으면 모두 통과, 보냈으면 일치하는 계정만 남긴다
                .filter(item -> request.active() == null || item.active() == request.active())
                .sorted(comparatorFor(request))
                .toList();
        // 페이지의 시작·끝 위치. page*size가 int를 넘을 수 있어 long으로 계산하고 목록 길이로 자른다(범위 밖이면 빈 페이지)
        int from = (int) Math.min((long) request.page() * request.size(), matched.size());
        int to = Math.min(from + request.size(), matched.size());
        // 마지막 인자 truncated는 상한 때문에 일부만 다뤘음을 응답으로 알린다
        return PageResponse.of(matched.subList(from, to), request.page(), request.size(), matched.size(), truncated);
    }

    // 관리자 정보 수정(FNC-A-010)이 수정된 계정을 목록 한 줄 모양으로 돌려줄 때도 쓴다
    static AdminListItem toItem(AdminRow row, AccountActivities activities) {
        // 세션이 있으면 세션 접근 시각, 없으면 마지막 로그인 시각이 '최근 활동'이다
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

    // DB의 KST 시각에 시간대를 붙여 +09:00 오프셋 시각으로 바꾼다(없으면 null)
    private static OffsetDateTime toOffset(LocalDateTime dateTime, ZoneId zone) {
        return dateTime == null ? null : dateTime.atZone(zone).toOffsetDateTime();
    }

    // 사용자 목록과 같은 규칙: 같은 값이면 번호로 한 번 더, 최근 활동이 없는 계정은 방향과 관계없이 맨 뒤
    private static Comparator<AdminListItem> comparatorFor(AdminSearchRequest request) {
        boolean ascending = request.isAscending();
        Comparator<AdminListItem> primary =
                switch (request.sortField()) {
                    case CREATED_AT -> by(AdminListItem::createdAt, ascending);
                    case LOGIN_ID -> by(AdminListItem::loginId, ascending);
                    case LAST_ACTIVE_AT -> by(AdminListItem::lastActiveAt, ascending);
                };
        return primary.thenComparing(by(AdminListItem::id, ascending));
    }

    // 값이 null(최근 활동 없음)인 계정은 오름·내림차순과 관계없이 맨 뒤로 보낸다
    private static <T extends Comparable<? super T>> Comparator<AdminListItem> by(
            Function<AdminListItem, T> key, boolean ascending) {
        Comparator<T> order = ascending ? Comparator.naturalOrder() : Comparator.reverseOrder();
        return Comparator.comparing(key, Comparator.nullsLast(order));
    }

    // 세션 조회에 쓸 로그인 아이디만 뽑는다
    private static List<String> loginIdsOf(List<AdminRow> rows) {
        return rows.stream().map(AdminRow::loginId).toList();
    }
}
