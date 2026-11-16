package com.whiteeyes.admin.event.service;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import lombok.RequiredArgsConstructor;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.whiteeyes.admin.event.dto.EventSearchRequest;
import com.whiteeyes.admin.event.dto.EventSummaryResponse;
import com.whiteeyes.admin.event.repository.EventSummaryQueryRepository;
import com.whiteeyes.admin.event.repository.EventSummaryQueryRepository.StatusTypeCount;
import com.whiteeyes.common.event.entity.Event;
import com.whiteeyes.common.event.entity.EventStatus;
import com.whiteeyes.common.event.entity.EventType;

/**
 * 이벤트 요약(FNC-A-036 처리 순서 3): 목록과 같은 조건에서 처리 상태 조건만 빼고 상태별 건수를 센다.
 * 같은 쿼리로 종류별 건수(byType)도 낸다(FNC-C-030 보조 지표 '오늘 이벤트 종류별 건수').
 */
@Service
@RequiredArgsConstructor
public class EventSummaryService {

    // 필드: 조회 조건 만들기, 묶어 세기 쿼리
    private final EventConditionService eventConditionService;
    private final EventSummaryQueryRepository eventSummaryQueryRepository;

    /**
     * 조건에 맞는 이벤트의 처리 상태별·종류별 건수를 센다.
     */
    @Transactional(readOnly = true)
    public EventSummaryResponse summarize(EventSearchRequest request) {
        Optional<Specification<Event>> condition = eventConditionService.toSpecification(request);
        // 맞는 구역이 없으면 쿼리 없이 모두 0인 응답
        if (condition.isEmpty()) {
            return EventSummaryResponse.empty();
        }
        // (상태, 종류)로 묶어 한 번에 세고, 상태별·종류별로 각각 더한다
        List<StatusTypeCount> counts = eventSummaryQueryRepository.countByStatusAndType(condition.get());
        // 처리 상태별·종류별 합계를 담을 맵
        Map<EventStatus, Long> byStatus = new EnumMap<>(EventStatus.class);
        Map<EventType, Long> byType = new EnumMap<>(EventType.class);
        // (상태, 종류)별 건수를 상태 합계와 종류 합계에 각각 누적한다
        for (StatusTypeCount count : counts) {
            byStatus.merge(count.status(), count.count(), Long::sum);
            byType.merge(count.type(), count.count(), Long::sum);
        }
        // 상태 맵에 없는 상태는 0으로 읽는다. 종류 맵의 빈 종류는 응답 객체가 0으로 채운다
        return EventSummaryResponse.of(
                byStatus.getOrDefault(EventStatus.UNCHECKED, 0L),
                byStatus.getOrDefault(EventStatus.CHECKED, 0L),
                byStatus.getOrDefault(EventStatus.RESOLVED, 0L),
                byType);
    }
}
