package com.whiteeyes.admin.history.repository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import com.whiteeyes.common.history.entity.ActorType;
import com.whiteeyes.common.history.entity.WorkAction;
import com.whiteeyes.common.history.entity.WorkHistory;

/**
 * 작업 기록 조회(FNC-A-053 처리 순서 1). 조건을 골라 붙여야 해서 Criteria로 만든다.
 * common의 WorkHistoryRepository는 쌓기만 하는 저장용이라 조회는 여기서 따로 한다(수정·삭제는 열지 않는다).
 */
@Repository
@RequiredArgsConstructor
public class WorkHistoryQueryRepository {

    // JPA Criteria API를 쓰기 위한 EntityManager
    private final EntityManager entityManager;

    /** 최신순(같은 시각이면 번호가 큰 순) 한 페이지. */
    public List<WorkHistory> findPage(Condition condition, int page, int size) {
        // int 곱셈 넘침을 피하려고 long으로 계산한다
        long offset = (long) page * size;
        // setFirstResult가 int만 받으므로, 그보다 뒤쪽 페이지는 기록이 있을 수 없다고 보고 빈 결과로 답한다
        if (offset > Integer.MAX_VALUE) {
            return List.of();
        }
        // 쿼리 조립 도구. SELECT ... WHERE ... ORDER BY created_at DESC, id DESC 를 만든다
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<WorkHistory> query = cb.createQuery(WorkHistory.class);
        Root<WorkHistory> root = query.from(WorkHistory.class);
        // 같은 시각 기록끼리도 순서가 흔들리지 않도록 번호를 두 번째 정렬 기준으로 쓴다
        query.select(root)
                .where(predicates(cb, root, condition))
                .orderBy(cb.desc(root.get("createdAt")), cb.desc(root.get("id")));
        // 건너뛸 행 수(offset)와 가져올 행 수(size)로 한 페이지만 읽는다
        return entityManager
                .createQuery(query)
                .setFirstResult((int) offset)
                .setMaxResults(size)
                .getResultList();
    }

    /** 조건에 맞는 전체 기록 수. */
    // 목록의 총 건수 계산용. findPage와 같은 조건을 쓰므로 페이지 수가 어긋나지 않는다
    public long count(Condition condition) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Long> query = cb.createQuery(Long.class);
        Root<WorkHistory> root = query.from(WorkHistory.class);
        // COUNT(*) 쿼리
        query.select(cb.count(root)).where(predicates(cb, root, condition));
        return entityManager.createQuery(query).getSingleResult();
    }

    /** 값이 있는 조건만 WHERE 절 조각으로 만든다(모두 AND로 묶인다). */
    private Predicate[] predicates(CriteriaBuilder cb, Root<WorkHistory> root, Condition condition) {
        // 고른 조건 조각 모음
        List<Predicate> predicates = new ArrayList<>();
        // 기록 시각 시작(이상)
        if (condition.from() != null) {
            predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), condition.from()));
        }
        // 기록 시각 끝(미만). 끝을 포함하지 않아 이어지는 기간 검색이 겹치지 않는다
        if (condition.to() != null) {
            predicates.add(cb.lessThan(root.get("createdAt"), condition.to()));
        }
        // 행동한 계정 종류(사용자·관리자)
        if (condition.actorType() != null) {
            predicates.add(cb.equal(root.get("actorType"), condition.actorType()));
        }
        // 기록 당시 아이디와 정확히 일치. 계정 테이블이 아니라 기록에 남은 값으로 거른다
        if (condition.loginId() != null) {
            predicates.add(cb.equal(root.get("actorLoginId"), condition.loginId()));
        }
        // 행동 종류
        if (condition.action() != null) {
            predicates.add(cb.equal(root.get("action"), condition.action()));
        }
        // 조건이 하나도 없으면 빈 배열이라 where()가 전체를 대상으로 한다
        return predicates.toArray(Predicate[]::new);
    }

    /** 조회 조건. 비운 값(null)은 거르지 않는다. 시각은 KST(DB 저장 형식)다. */
    public record Condition(
            LocalDateTime from, LocalDateTime to, ActorType actorType, String loginId, WorkAction action) {}
}
