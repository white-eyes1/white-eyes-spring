package com.whiteeyes.admin.account.repository;

import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import com.whiteeyes.admin.account.dto.AccountSortField;
import com.whiteeyes.admin.account.dto.AdminSearchRequest;
import com.whiteeyes.admin.account.entity.AdminAccount;
import com.whiteeyes.admin.account.entity.AdminStatus;

/**
 * 관리자 목록 조회(FNC-A-003 처리 순서 1, 'FNC-A-002와 같은 방식'). 검색어·상태 조건을 골라 붙여야 해서 Criteria로 만든다.
 * 목록에 필요한 칼럼만 AdminRow로 읽는다(비밀번호는 읽지 않음). 정렬·후보 규칙은 UserAccountQueryRepository와 같다.
 */
@Repository
@RequiredArgsConstructor
public class AdminAccountQueryRepository {

    // UserAccountQueryRepository와 같은 이스케이프 문자
    private static final char LIKE_ESCAPE = '!';

    // JPA 표준 EntityManager. 조건이 동적이라 Criteria 쿼리를 직접 만들어 실행한다
    private final EntityManager entityManager;

    /** 조건·정렬을 적용한 한 페이지. 최근 활동 정렬은 세션 정보가 있어야 하므로 여기서 다루지 않는다. */
    public List<AdminRow> findPage(AdminSearchRequest request) {
        // 최근 활동은 DB 칼럼이 아니라 세션 표에서 계산하는 값이라 DB 정렬이 불가능하다.
        // 서비스가 isSessionDependent()로 메모리 처리 경로로 보내므로, 여기 오면 호출이 잘못된 것이라 예외로 막는다
        if (request.sortField() == AccountSortField.LAST_ACTIVE_AT) {
            throw new IllegalArgumentException("최근 활동 정렬은 메모리에서 처리한다");
        }
        // 페이지 번호 * 크기는 int 범위를 넘을 수 있어 long으로 계산한다
        long offset = (long) request.page() * request.size();
        if (offset > Integer.MAX_VALUE) {
            return List.of();
        }
        // 조건·정렬이 붙은 쿼리에 시작 위치와 개수를 걸어 한 페이지만 읽는다
        return select(request, sortOrder(request.sortField(), request.isAscending()))
                .setFirstResult((int) offset)
                .setMaxResults(request.size())
                .getResultList();
    }

    /** 조건에 맞는 전체 계정 수. */
    public long count(AdminSearchRequest request) {
        // Criteria 쿼리를 만드는 도구. 아래에서 count 쿼리를 만든다
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Long> query = cb.createQuery(Long.class);
        Root<AdminAccount> root = query.from(AdminAccount.class);
        // 목록과 같은 조건(conditions)으로 개수만 센다. 같은 메서드를 써서 목록과 전체 개수의 기준이 어긋나지 않게 한다
        query.select(cb.count(root)).where(conditions(cb, root, request));
        return entityManager.createQuery(query).getSingleResult();
    }

    /** 메모리에서 거르고 정렬할 후보를 limit명까지 읽는다(사용자 목록과 같은 규칙). */
    public List<AdminRow> findCandidates(AdminSearchRequest request, int limit) {
        // 최근 활동 정렬이면 DB에서는 가장 비슷한 '마지막 로그인 최근 순'으로 상한까지 읽고, 정확한 정렬은 서비스가 세션 정보로 메모리에서 한다
        List<SortKey> sortKeys = request.sortField() == AccountSortField.LAST_ACTIVE_AT
                ? List.of(new SortKey("lastLoginAt", false), new SortKey("id", false))
                : sortOrder(request.sortField(), request.isAscending());
        // 정렬한 앞쪽 limit개까지만 읽는다
        return select(request, sortKeys).setMaxResults(limit).getResultList();
    }

    private TypedQuery<AdminRow> select(AdminSearchRequest request, List<SortKey> sortKeys) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        // 엔티티 전체가 아니라 목록에 필요한 칼럼만 AdminRow로 바로 담는다(비밀번호 등은 읽지 않음)
        CriteriaQuery<AdminRow> query = cb.createQuery(AdminRow.class);
        Root<AdminAccount> root = query.from(AdminAccount.class);
        // 아래 칼럼 순서는 AdminRow 생성자의 매개변수 순서와 같아야 한다
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
        // 값이 들어온 조건만 모아 AND로 이어 붙인다. 안 들어온 조건은 거르지 않는다
        List<Predicate> predicates = new ArrayList<>();
        if (request.keyword() != null) {
            // 앞뒤에 %를 붙여 아이디나 이름 어디에든 검색어가 들어 있으면 맞는 부분 일치로 만든다
            String pattern = "%" + escapeLike(request.keyword()) + "%";
            // 아이디 또는 이름 중 하나만 맞아도 된다(OR)
            predicates.add(cb.or(
                    cb.like(root.get("loginId"), pattern, LIKE_ESCAPE),
                    cb.like(root.get("name"), pattern, LIKE_ESCAPE)));
        }
        if (request.status() != null) {
            predicates.add(cb.equal(root.get("status"), AdminStatus.valueOf(request.status())));
        }
        // where(...)가 배열을 받으므로 배열로 바꾼다. 여러 조건은 모두 만족해야 한다(AND)
        return predicates.toArray(Predicate[]::new);
    }

    // LIKE에서 특별한 문자(%, _)와 이스케이프 문자(!) 앞에 !를 붙여 글자 그대로 찾게 한다.
    // !를 가장 먼저 바꿔야 뒤 치환이 만든 !가 다시 바뀌지 않는다
    private static String escapeLike(String keyword) {
        return keyword.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }

    // 정렬 기준을 엔티티 속성 이름으로 바꾸고, 2차 기준으로 번호(id)를 같은 방향으로 덧붙인다.
    // 값이 같은 행끼리도 순서가 고정되어 페이지를 넘길 때 중복·누락이 생기지 않는다
    private static List<SortKey> sortOrder(AccountSortField field, boolean ascending) {
        String attribute =
                switch (field) {
                    case CREATED_AT -> "createdAt";
                    case LOGIN_ID -> "loginId";
                        // 최근 활동은 세션에서 계산하는 값이라 여기서 쓸 수 없다
                    case LAST_ACTIVE_AT -> throw new IllegalArgumentException("최근 활동은 DB 칼럼이 아니다");
                };
        return List.of(new SortKey(attribute, ascending), new SortKey("id", ascending));
    }

    // 정렬 키 목록을 Criteria의 오름차순/내림차순 정렬 객체로 바꾼다
    private static List<Order> toOrders(CriteriaBuilder cb, Root<AdminAccount> root, List<SortKey> sortKeys) {
        return sortKeys.stream()
                .map(sortKey -> {
                    Expression<?> path = root.get(sortKey.attribute());
                    return sortKey.ascending() ? cb.asc(path) : cb.desc(path);
                })
                .toList();
    }

    // 정렬할 엔티티 속성 이름과 방향(오름차순이면 true)을 묶은 값
    private record SortKey(String attribute, boolean ascending) {}
}
