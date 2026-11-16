package com.whiteeyes.admin.event.repository;

import java.util.List;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import lombok.RequiredArgsConstructor;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Repository;

import com.whiteeyes.common.event.entity.Event;
import com.whiteeyes.common.event.entity.EventStatus;
import com.whiteeyes.common.event.entity.EventType;

/**
 * 이벤트 요약(FNC-A-036, FNC-C-030)의 건수를 처리 상태·종류로 묶어 쿼리 한 번에 센다.
 * 목록과 같은 조건(Specification)을 그대로 쓰는데 Spring Data의 Specification 조회는 묶어 세기를 못 해서 Criteria로 만든다.
 */
@Repository
@RequiredArgsConstructor
public class EventSummaryQueryRepository {

    // JPA Criteria API를 쓰기 위한 EntityManager
    private final EntityManager entityManager;

    /** 조건에 맞는 이벤트를 (처리 상태, 종류)마다 센다. 0건인 묶음은 빠진다. */
    public List<StatusTypeCount> countByStatusAndType(Specification<Event> condition) {
        // 쿼리 조립 도구. SELECT status, type, COUNT(*) ... GROUP BY status, type 을 만든다
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Tuple> query = cb.createTupleQuery();
        Root<Event> root = query.from(Event.class);
        // 선택·묶음에 같이 쓸 컬럼 경로를 변수로 둔다(결과에서 같은 객체로 값을 꺼내기 위해서)
        Path<EventStatus> status = root.get("status");
        Path<EventType> type = root.get("type");
        Expression<Long> count = cb.count(root);
        // 처리 상태, 종류, 건수를 고르고 상태·종류로 묶는다
        query.multiselect(status, type, count).groupBy(status, type);
        // 목록과 같은 Specification에서 WHERE 조건을 얻는다
        Predicate predicate = condition.toPredicate(root, query, cb);
        // 조건이 없는 Specification은 null을 돌려주므로 있을 때만 WHERE를 붙인다
        if (predicate != null) {
            query.where(predicate);
        }
        // 결과 행을 (상태, 종류, 건수) 레코드로 바꾼다
        return entityManager.createQuery(query).getResultList().stream()
                .map(row -> new StatusTypeCount(row.get(status), row.get(type), row.get(count)))
                .toList();
    }

    /** (처리 상태, 종류) 묶음 하나의 건수. */
    public record StatusTypeCount(EventStatus status, EventType type, long count) {}
}
