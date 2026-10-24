package com.whiteeyes.admin.history.repository;

import com.whiteeyes.common.history.entity.ActorType;
import com.whiteeyes.common.history.entity.WorkAction;
import com.whiteeyes.common.history.entity.WorkHistory;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * 작업 기록 조회(FNC-A-053 처리 순서 1). 조건을 골라 붙여야 해서 Criteria로 만든다.
 * common의 WorkHistoryRepository는 쌓기만 하는 저장용이라 조회는 여기서 따로 한다(수정·삭제는 열지 않는다).
 */
@Repository
@RequiredArgsConstructor
public class WorkHistoryQueryRepository {

    private final EntityManager entityManager;

    /** 최신순(같은 시각이면 번호가 큰 순) 한 페이지. */
    public List<WorkHistory> findPage(Condition condition, int page, int size) {
        long offset = (long) page * size;
        if (offset > Integer.MAX_VALUE) {
            return List.of();
        }
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<WorkHistory> query = cb.createQuery(WorkHistory.class);
        Root<WorkHistory> root = query.from(WorkHistory.class);
        query.select(root)
                .where(predicates(cb, root, condition))
                .orderBy(cb.desc(root.get("createdAt")), cb.desc(root.get("id")));
        return entityManager
                .createQuery(query)
                .setFirstResult((int) offset)
                .setMaxResults(size)
                .getResultList();
    }

    /** 조건에 맞는 전체 기록 수. */
    public long count(Condition condition) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Long> query = cb.createQuery(Long.class);
        Root<WorkHistory> root = query.from(WorkHistory.class);
        query.select(cb.count(root)).where(predicates(cb, root, condition));
        return entityManager.createQuery(query).getSingleResult();
    }

    private Predicate[] predicates(CriteriaBuilder cb, Root<WorkHistory> root, Condition condition) {
        List<Predicate> predicates = new ArrayList<>();
        if (condition.from() != null) {
            predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), condition.from()));
        }
        if (condition.to() != null) {
            predicates.add(cb.lessThan(root.get("createdAt"), condition.to()));
        }
        if (condition.actorType() != null) {
            predicates.add(cb.equal(root.get("actorType"), condition.actorType()));
        }
        if (condition.loginId() != null) {
            predicates.add(cb.equal(root.get("actorLoginId"), condition.loginId()));
        }
        if (condition.action() != null) {
            predicates.add(cb.equal(root.get("action"), condition.action()));
        }
        return predicates.toArray(Predicate[]::new);
    }

    /** 조회 조건. 비운 값(null)은 거르지 않는다. 시각은 KST(DB 저장 형식)다. */
    public record Condition(
            LocalDateTime from, LocalDateTime to, ActorType actorType, String loginId, WorkAction action) {}
}
