package com.whiteeyes.admin.account.repository;

import com.whiteeyes.admin.account.dto.UserSearchRequest;
import com.whiteeyes.admin.account.dto.AccountSortField;
import com.whiteeyes.common.account.entity.UserAccount;
import com.whiteeyes.common.account.entity.UserAccountStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * 사용자 목록 조회(FNC-A-002 처리 순서 1). 검색어·상태·가입일 조건을 골라 붙여야 해서 Criteria로 만든다.
 * 목록에 필요한 칼럼만 UserRow로 읽는다(비밀번호는 읽지 않음).
 */
@Repository
@RequiredArgsConstructor
public class UserAccountQueryRepository {

    // MySQL에서 백슬래시는 문자열 안 이스케이프로도 쓰여 헷갈리므로 LIKE 이스케이프 문자로 '!'를 쓴다
    private static final char LIKE_ESCAPE = '!';

    private final EntityManager entityManager;

    /** 조건·정렬을 적용한 한 페이지. 최근 활동 정렬은 세션 정보가 있어야 하므로 여기서 다루지 않는다. */
    public List<UserRow> findPage(UserSearchRequest request) {
        if (request.sortField() == AccountSortField.LAST_ACTIVE_AT) {
            throw new IllegalArgumentException("최근 활동 정렬은 메모리에서 처리한다");
        }
        long offset = (long) request.page() * request.size();
        // page에는 상한이 없어, 읽을 수 있는 범위를 넘으면 빈 페이지다
        if (offset > Integer.MAX_VALUE) {
            return List.of();
        }
        return select(request, sortOrder(request.sortField(), request.isAscending()))
                .setFirstResult((int) offset)
                .setMaxResults(request.size())
                .getResultList();
    }

    /** 조건에 맞는 전체 계정 수. */
    public long count(UserSearchRequest request) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Long> query = cb.createQuery(Long.class);
        Root<UserAccount> root = query.from(UserAccount.class);
        query.select(cb.count(root)).where(conditions(cb, root, request));
        return entityManager.createQuery(query).getSingleResult();
    }

    /**
     * 메모리에서 거르고 정렬할 후보를 limit명까지 읽는다. 상한에 걸리면 이 순서의 앞쪽이 남는다(#32에서 정함).
     * 최근 활동 정렬이면 그와 가장 가까운 마지막 로그인 최근 순, 아니면 요청한 정렬 순이다.
     */
    public List<UserRow> findCandidates(UserSearchRequest request, int limit) {
        List<SortKey> sortKeys = request.sortField() == AccountSortField.LAST_ACTIVE_AT
                ? lastLoginRecentFirst()
                : sortOrder(request.sortField(), request.isAscending());
        return select(request, sortKeys).setMaxResults(limit).getResultList();
    }

    private TypedQuery<UserRow> select(UserSearchRequest request, List<SortKey> sortKeys) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<UserRow> query = cb.createQuery(UserRow.class);
        Root<UserAccount> root = query.from(UserAccount.class);
        query.select(cb.construct(
                        UserRow.class,
                        root.get("id"),
                        root.get("loginId"),
                        root.get("name"),
                        root.get("email"),
                        root.get("phone"),
                        root.get("status"),
                        root.get("createdAt"),
                        root.get("lastLoginAt")))
                .where(conditions(cb, root, request))
                .orderBy(toOrders(cb, root, sortKeys));
        return entityManager.createQuery(query);
    }

    private Predicate[] conditions(CriteriaBuilder cb, Root<UserAccount> root, UserSearchRequest request) {
        List<Predicate> predicates = new ArrayList<>();
        if (request.keyword() != null) {
            String pattern = "%" + escapeLike(request.keyword()) + "%";
            predicates.add(cb.or(
                    cb.like(root.get("loginId"), pattern, LIKE_ESCAPE),
                    cb.like(root.get("name"), pattern, LIKE_ESCAPE)));
        }
        if (request.status() != null) {
            predicates.add(cb.equal(root.get("status"), UserAccountStatus.valueOf(request.status())));
        }
        if (request.joinedFromInclusive() != null) {
            predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), request.joinedFromInclusive()));
        }
        if (request.joinedToExclusive() != null) {
            predicates.add(cb.lessThan(root.get("createdAt"), request.joinedToExclusive()));
        }
        return predicates.toArray(Predicate[]::new);
    }

    // 검색어의 %와 _는 와일드카드가 아니라 글자 그대로 찾는다(#32에서 정함)
    private static String escapeLike(String keyword) {
        return keyword.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }

    // 같은 값이면 번호로 한 번 더 정렬해 페이지를 넘겨도 순서가 흔들리지 않게 한다(UserListAssembler와 같은 규칙)
    private static List<SortKey> sortOrder(AccountSortField field, boolean ascending) {
        String attribute = switch (field) {
            case CREATED_AT -> "createdAt";
            case LOGIN_ID -> "loginId";
            case LAST_ACTIVE_AT -> throw new IllegalArgumentException("최근 활동은 DB 칼럼이 아니다");
        };
        return List.of(new SortKey(attribute, ascending), new SortKey("id", ascending));
    }

    // 로그인 기록이 없는 계정(null)은 MySQL·H2 모두 내림차순에서 맨 뒤로 간다
    private static List<SortKey> lastLoginRecentFirst() {
        return List.of(new SortKey("lastLoginAt", false), new SortKey("id", false));
    }

    private static List<Order> toOrders(CriteriaBuilder cb, Root<UserAccount> root, List<SortKey> sortKeys) {
        return sortKeys.stream()
                .map(sortKey -> {
                    Expression<?> path = root.get(sortKey.attribute());
                    return sortKey.ascending() ? cb.asc(path) : cb.desc(path);
                })
                .toList();
    }

    private record SortKey(String attribute, boolean ascending) {}
}
