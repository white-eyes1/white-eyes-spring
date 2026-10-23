package com.whiteeyes.admin.event.service;

import com.whiteeyes.admin.event.dto.EventSearchRequest;
import com.whiteeyes.admin.event.dto.EventSummaryResponse;
import com.whiteeyes.common.event.entity.Event;
import com.whiteeyes.common.event.entity.EventStatus;
import com.whiteeyes.common.event.repository.EventRepository;
import com.whiteeyes.common.event.repository.EventSpecifications;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 이벤트 요약(FNC-A-036 처리 순서 3): 목록과 같은 조건에서 처리 상태 조건만 빼고 상태별 건수를 센다. */
@Service
@RequiredArgsConstructor
public class EventSummaryService {

    private final EventConditionService eventConditionService;
    private final EventRepository eventRepository;

    @Transactional(readOnly = true)
    public EventSummaryResponse summarize(EventSearchRequest request) {
        Optional<Specification<Event>> condition = eventConditionService.toSpecification(request);
        if (condition.isEmpty()) {
            return EventSummaryResponse.empty();
        }
        return EventSummaryResponse.of(
                count(condition.get(), EventStatus.UNCHECKED),
                count(condition.get(), EventStatus.CHECKED),
                count(condition.get(), EventStatus.RESOLVED));
    }

    private long count(Specification<Event> condition, EventStatus status) {
        return eventRepository.count(Specification.allOf(condition, EventSpecifications.ofStatus(status)));
    }
}
