package com.whiteeyes.admin.account.repository;

import com.whiteeyes.admin.account.dto.AccountSortField;
import com.whiteeyes.admin.account.dto.AdminSearchRequest;
import com.whiteeyes.admin.account.entity.AdminAccount;
import com.whiteeyes.admin.account.entity.AdminStatus;
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
 * 관리자 목록 조회(FNC-A-003 처리 순서 1, 'FNC-A-002와 같은 방식'). 검색어·상태 조건을 골라 붙여야 해서 Criteria로 만든다.
 * 목록에 필요한 칼럼만 AdminRow로 읽는다(비밀번호는 읽지 않음). 정렬·후보 규칙은 UserAccountQueryRepository와 같다.
 */
@Repository
@RequiredArgsConstructor
public class AdminAccountQueryRepository {

    // UserAccountQueryRepository와 같은 이스케이프 문자
    private static final char LIKE_ESCAPE = '!';

    private final EntityManager entityManager;

    /** 조건·정렬을 적용한 한 페이지. 최근 활동 정렬은 세션 정보가 있어야 하므로 여기서 다루지 않는다. */
    public List<AdminRow> findPage(AdminSearchRequest request) {
        if (request.sortField() == AccountSortField.LAST_ACTIVE_AT) {
            throw new IllegalArgumentException("최근 활동 정렬은 메모리에서 처리한다");
        }
        long offset = (long) request.page() * request.size();
        if (offset > Integer.MAX_VALUE) {
            return List.of();
        }
        return select(request, sortOrder(request.sortField(), request.isAscending()))
                .setFirstResult((int) offset)
                .setMaxResults(request.size())
                .getResultList();
    }

    /** 조건에 맞는 전체 계정 수. */
    public long count(AdminSearchRequest request) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Long> query = cb.createQuery(Long.class);
        Root<AdminAccount> root = query.from(AdminAccount.class);
        query.select(cb.count(root)).where(conditions(cb, root, request));
        return entityManager.createQuery(query).getSingleResult();
    }

    /** 메모리에서 거르고 정렬할 후보를 limit명까지 읽는다(사용자 목록과 같은 규칙). */
    public List<AdminRow> findCandidates(AdminSearchRequest request, int limit) {
        List<SortKey> sortKeys = request.sortField() == AccountSortField.LAST_ACTIVE_AT
                ? List.of(new SortKey("lastLoginAt", false), new SortKey("id", false))
                : sortOrder(request.sortField(), request.isAscending());
        return select(request, sortKeys).setMaxResults(limit).getResultList();
    }

    private TypedQuery<AdminRow> select(AdminSearchRequest request, List<SortKey> sortKeys) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<AdminRow> query = cb.createQuery(AdminRow.class);
        Root<AdminAccount> root = query.from(AdminAccount.class);
        query.select(cb.construct(
                        AdminRow.class,
                        root.get("id"),
                        root.get("loginId"),
                        root.get("name"),
                        root.get("email"),
                        root.get("phone"),
                        root.get("role"),
                        root.get("status"),
                        root.get("createdAt"),
                        root.get("lastLoginAt")))
                .where(conditions(cb, root, request))
                .orderBy(toOrders(cb, root, sortKeys));
        return entityManager.createQuery(query);
    }

    private Predicate[] conditions(CriteriaBuilder cb, Root<AdminAccount> root, AdminSearchRequest request) {
        List<Predicate> predicates = new ArrayList<>();
        if (request.keyword() != null) {
            String pattern = "%" + escapeLike(request.keyword()) + "%";
            predicates.add(cb.or(
                    cb.like(root.get("loginId"), pattern, LIKE_ESCAPE),
                    cb.like(root.get("name"), pattern, LIKE_ESCAPE)));
        }
        if (request.status() != null) {
            predicates.add(cb.equal(root.get("status"), AdminStatus.valueOf(request.status())));
        }
        return predicates.toArray(Predicate[]::new);
    }

    private static String escapeLike(String keyword) {
        return keyword.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }

    private static List<SortKey> sortOrder(AccountSortField field, boolean ascending) {
        String attribute = switch (field) {
            case CREATED_AT -> "createdAt";
            case LOGIN_ID -> "loginId";
            case LAST_ACTIVE_AT -> throw new IllegalArgumentException("최근 활동은 DB 칼럼이 아니다");
        };
        return List.of(new SortKey(attribute, ascending), new SortKey("id", ascending));
    }

    private static List<Order> toOrders(CriteriaBuilder cb, Root<AdminAccount> root, List<SortKey> sortKeys) {
        return sortKeys.stream()
                .map(sortKey -> {
                    Expression<?> path = root.get(sortKey.attribute());
                    return sortKey.ascending() ? cb.asc(path) : cb.desc(path);
                })
                .toList();
    }

    private record SortKey(String attribute, boolean ascending) {}
}
