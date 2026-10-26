package com.whiteeyes.common.event.repository;

import com.whiteeyes.common.event.entity.Event;
import com.whiteeyes.common.event.entity.EventStatus;
import com.whiteeyes.common.event.entity.EventType;
import jakarta.persistence.criteria.Predicate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.domain.Specification;

/** 이벤트 목록·요약(FNC-A-036)의 조회 조건. 조건마다 하나씩 만들어 {@link Specification#allOf}로 묶어 쓴다. */
public final class EventSpecifications {

    private EventSpecifications() {}

    /** 발생 시각이 from 이상 to 미만(conventions.md '기간 조회'). 비운 쪽은 거르지 않는다. */
    public static Specification<Event> startedBetween(LocalDateTime from, LocalDateTime to) {
        return (root, query, cb) -> {
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
            return cb.conjunction();
        };
    }

    /**
     * 기간 [from, to)와 겹치는 이벤트: 발생이 to 앞이고, 종료가 없거나(진행 중) from 뒤인 것. 발생이 from 앞이어도
     * 그 기간 안에서 이어진 이벤트를 포함한다(다시보기 시간축, #198). 비운 쪽은 거르지 않는다.
     */
    public static Specification<Event> overlapping(LocalDateTime from, LocalDateTime to) {
        return (root, query, cb) -> {
            List<Predicate> conditions = new ArrayList<>();
            if (to != null) {
                conditions.add(cb.lessThan(root.get("startedAt"), to));
            }
            if (from != null) {
                conditions.add(cb.or(cb.isNull(root.get("endedAt")), cb.greaterThan(root.get("endedAt"), from)));
            }
            return conditions.isEmpty() ? cb.conjunction() : cb.and(conditions.toArray(Predicate[]::new));
        };
    }

    /** 구역이 주어진 것 중 하나. 빈 목록이면 아무것도 맞지 않는다. */
    public static Specification<Event> inZones(Collection<Long> zoneIds) {
        return (root, query, cb) -> zoneIds.isEmpty() ? cb.disjunction() : root.get("zoneId").in(zoneIds);
    }

    /** 종류가 주어진 것 중 하나. 빈 목록이면 거르지 않는다. */
    public static Specification<Event> ofTypes(Collection<EventType> types) {
        return (root, query, cb) -> types.isEmpty() ? cb.conjunction() : root.get("type").in(types);
    }

    /** 처리 상태가 주어진 것 중 하나. 빈 목록이면 거르지 않는다. */
    public static Specification<Event> ofStatuses(Collection<EventStatus> statuses) {
        return (root, query, cb) -> statuses.isEmpty() ? cb.conjunction() : root.get("status").in(statuses);
    }

    /** 처리 상태가 정확히 이것. */
    public static Specification<Event> ofStatus(EventStatus status) {
        return (root, query, cb) -> cb.equal(root.get("status"), status);
    }
}
