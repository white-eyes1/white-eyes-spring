package com.whiteeyes.common.event.service;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.whiteeyes.common.event.entity.EventType;
import com.whiteeyes.common.event.repository.EventRepository;

/**
 * 구역별 진행 중 이벤트 여부(FNC-C-009 처리 순서 5). 아직 닫히지 않은 혼잡·급증만 보고 캠 이상은 보지 않는다(das-003).
 * 다른 도메인은 이벤트 리포지토리가 아니라 이 서비스를 부른다.
 */
@Service
public class OngoingEventService {

    private static final List<EventType> COUNTED_TYPES = List.of(EventType.CONGESTION, EventType.SURGE);

    private final EventRepository eventRepository;

    public OngoingEventService(EventRepository eventRepository) {
        this.eventRepository = eventRepository;
    }

    /**
     * 열린(종료 시각 없는) 혼잡·급증 이벤트가 있는 구역을 한 번의 쿼리로 찾는다. 처리 상태는 보지 않는다.
     *
     * @param zoneIds 삭제되지 않은 구역 번호(삭제된 구역은 지도에 보이지 않으므로 넘기지 않는다)
     * @return 열린 이벤트가 있는 구역 번호. 없으면 빈 집합
     */
    @Transactional(readOnly = true)
    public Set<Long> findZoneIdsWithOngoingEvent(Collection<Long> zoneIds) {
        if (zoneIds.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(eventRepository.findZoneIdsWithOpenEvent(zoneIds, COUNTED_TYPES));
    }
}
