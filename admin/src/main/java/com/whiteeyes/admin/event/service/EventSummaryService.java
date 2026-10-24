package com.whiteeyes.admin.event.service;

import com.whiteeyes.admin.event.dto.EventSearchRequest;
import com.whiteeyes.admin.event.dto.EventSummaryResponse;
import com.whiteeyes.admin.event.repository.EventSummaryQueryRepository;
import com.whiteeyes.admin.event.repository.EventSummaryQueryRepository.StatusTypeCount;
import com.whiteeyes.common.event.entity.Event;
import com.whiteeyes.common.event.entity.EventStatus;
import com.whiteeyes.common.event.entity.EventType;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 이벤트 요약(FNC-A-036 처리 순서 3): 목록과 같은 조건에서 처리 상태 조건만 빼고 상태별 건수를 센다.
 * 같은 쿼리로 종류별 건수(byType)도 낸다(FNC-C-030 보조 지표 '오늘 이벤트 종류별 건수').
 */
@Service
@RequiredArgsConstructor
public class EventSummaryService {

    private final EventConditionService eventConditionService;
    private final EventSummaryQueryRepository eventSummaryQueryRepository;

    @Transactional(readOnly = true)
    public EventSummaryResponse summarize(EventSearchRequest request) {
        Optional<Specification<Event>> condition = eventConditionService.toSpecification(request);
        if (condition.isEmpty()) {
            return EventSummaryResponse.empty();
        }
        // (상태, 종류)로 묶어 한 번에 세고, 상태별·종류별로 각각 더한다
        List<StatusTypeCount> counts = eventSummaryQueryRepository.countByStatusAndType(condition.get());
        Map<EventStatus, Long> byStatus = new EnumMap<>(EventStatus.class);
        Map<EventType, Long> byType = new EnumMap<>(EventType.class);
        for (StatusTypeCount count : counts) {
            byStatus.merge(count.status(), count.count(), Long::sum);
            byType.merge(count.type(), count.count(), Long::sum);
        }
        return EventSummaryResponse.of(
                byStatus.getOrDefault(EventStatus.UNCHECKED, 0L),
                byStatus.getOrDefault(EventStatus.CHECKED, 0L),
                byStatus.getOrDefault(EventStatus.RESOLVED, 0L),
                byType);
    }
}
