package com.whiteeyes.common.event.repository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import com.whiteeyes.common.event.entity.Event;
import com.whiteeyes.common.event.entity.EventStatus;
import com.whiteeyes.common.event.entity.EventType;

/** 이벤트 목록·요약(FNC-A-036)의 조회 조건. 조건마다 하나씩 만들어 {@link Specification#allOf}로 묶어 쓴다. */
public final class EventSpecifications {

    // 정적 메서드만 모은 유틸 클래스라 객체를 만들지 못하게 막는다. 반환하는 Specification은 JPA Criteria 조건 한 조각이다
    private EventSpecifications() {}

    /** 발생 시각이 from 이상 to 미만(conventions.md '기간 조회'). 비운 쪽은 거르지 않는다. */
    public static Specification<Event> startedBetween(LocalDateTime from, LocalDateTime to) {
        return (root, query, cb) -> {
            // 양쪽이 모두 있으면 [from, to) 구간: from 이상, to 미만(끝 시각은 포함하지 않는 반열린 구간)
            if (from != null && to != null) {
                return cb.and(
                        cb.greaterThanOrEqualTo(root.get("startedAt"), from), cb.lessThan(root.get("startedAt"), to));
            }
            if (from != null) {
                return cb.greaterThanOrEqualTo(root.get("startedAt"), from);
            }
            if (to != null) {
                return cb.lessThan(root.get("startedAt"), to);
            }
            // 둘 다 없으면 항상 참인 조건을 돌려줘 아무것도 거르지 않는다
            return cb.conjunction();
        };
    }

    /**
     * 기간 [from, to)와 겹치는 이벤트: 발생이 to 앞이고, 종료가 없거나(진행 중) from 뒤인 것. 발생이 from 앞이어도
     * 그 기간 안에서 이어진 이벤트를 포함한다(다시보기 시간축, #198). 비운 쪽은 거르지 않는다.
     */
    public static Specification<Event> overlapping(LocalDateTime from, LocalDateTime to) {
        return (root, query, cb) -> {
            // 값이 있는 쪽의 조건만 모아 AND로 묶는다
            List<Predicate> conditions = new ArrayList<>();
            if (to != null) {
                // 발생이 기간 끝보다 앞이어야 기간과 겹칠 수 있다
                conditions.add(cb.lessThan(root.get("startedAt"), to));
            }
            if (from != null) {
                // 아직 진행 중(종료 없음)이거나, 종료가 기간 시작보다 뒤여야 겹친다
                conditions.add(cb.or(cb.isNull(root.get("endedAt")), cb.greaterThan(root.get("endedAt"), from)));
            }
            return conditions.isEmpty() ? cb.conjunction() : cb.and(conditions.toArray(Predicate[]::new));
        };
    }

    /** 구역이 주어진 것 중 하나. 빈 목록이면 아무것도 맞지 않는다. */
    public static Specification<Event> inZones(Collection<Long> zoneIds) {
        return (root, query, cb) ->
                // 구역을 지정했는데 목록이 비어 있으면 맞는 구역이 없으므로 항상 거짓(disjunction)으로 결과를 비운다. 종류·상태와 반대다
                zoneIds.isEmpty() ? cb.disjunction() : root.get("zoneId").in(zoneIds);
    }

    /** 종류가 주어진 것 중 하나. 빈 목록이면 거르지 않는다. */
    public static Specification<Event> ofTypes(Collection<EventType> types) {
        return (root, query, cb) ->
                // 종류를 지정하지 않았으면 모든 종류를 보여 주므로 항상 참(conjunction)이다
                types.isEmpty() ? cb.conjunction() : root.get("type").in(types);
    }

    /** 처리 상태가 주어진 것 중 하나. 빈 목록이면 거르지 않는다. */
    public static Specification<Event> ofStatuses(Collection<EventStatus> statuses) {
        return (root, query, cb) ->
                statuses.isEmpty() ? cb.conjunction() : root.get("status").in(statuses);
    }

    /** 처리 상태가 정확히 이것. */
    public static Specification<Event> ofStatus(EventStatus status) {
        return (root, query, cb) -> cb.equal(root.get("status"), status);
    }
}
