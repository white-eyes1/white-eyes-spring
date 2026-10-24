package com.whiteeyes.admin.event.repository;

import com.whiteeyes.common.event.entity.Event;
import com.whiteeyes.common.event.entity.EventStatus;
import com.whiteeyes.common.event.entity.EventType;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Repository;

/**
 * 이벤트 요약(FNC-A-036, FNC-C-030)의 건수를 처리 상태·종류로 묶어 쿼리 한 번에 센다.
 * 목록과 같은 조건(Specification)을 그대로 쓰는데 Spring Data의 Specification 조회는 묶어 세기를 못 해서 Criteria로 만든다.
 */
@Repository
@RequiredArgsConstructor
public class EventSummaryQueryRepository {

    private final EntityManager entityManager;

    /** 조건에 맞는 이벤트를 (처리 상태, 종류)마다 센다. 0건인 묶음은 빠진다. */
    public List<StatusTypeCount> countByStatusAndType(Specification<Event> condition) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Tuple> query = cb.createTupleQuery();
        Root<Event> root = query.from(Event.class);
        Path<EventStatus> status = root.get("status");
        Path<EventType> type = root.get("type");
        Expression<Long> count = cb.count(root);
        query.multiselect(status, type, count).groupBy(status, type);
        Predicate predicate = condition.toPredicate(root, query, cb);
        if (predicate != null) {
            query.where(predicate);
        }
        return entityManager.createQuery(query).getResultList().stream()
                .map(row -> new StatusTypeCount(row.get(status), row.get(type), row.get(count)))
                .toList();
    }

    /** (처리 상태, 종류) 묶음 하나의 건수. */
    public record StatusTypeCount(EventStatus status, EventType type, long count) {}
}
