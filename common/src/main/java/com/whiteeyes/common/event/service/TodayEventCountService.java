package com.whiteeyes.common.event.service;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.whiteeyes.common.event.entity.EventType;
import com.whiteeyes.common.event.repository.EventRepository;

/**
 * 구역별 오늘 이벤트 수(FNC-C-017). 혼잡·급증만 세고 캠 이상은 세지 않는다(das-003).
 * 다른 도메인은 이벤트 리포지토리가 아니라 이 서비스를 부른다.
 */
@Service
public class TodayEventCountService {

    private static final List<EventType> COUNTED_TYPES = List.of(EventType.CONGESTION, EventType.SURGE);

    private final EventRepository eventRepository;

    public TodayEventCountService(EventRepository eventRepository) {
        this.eventRepository = eventRepository;
    }

    /**
     * 구역마다 from 이후에 시작한 혼잡·급증 이벤트 수를 한 번의 쿼리로 센다.
     *
     * @param from 오늘 00:00(KST)
     * @return 구역 번호 → 이벤트 수. 이벤트가 없는 구역은 들어 있지 않으므로 받는 쪽에서 0으로 읽는다
     */
    @Transactional(readOnly = true)
    public Map<Long, Integer> countByZone(Collection<Long> zoneIds, LocalDateTime from) {
        if (zoneIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Integer> countByZone = new HashMap<>();
        for (EventRepository.ZoneEventCount row : eventRepository.countByZone(zoneIds, COUNTED_TYPES, from)) {
            countByZone.put(row.getZoneId(), Math.toIntExact(row.getEventCount()));
        }
        return countByZone;
    }
}
