package com.whiteeyes.common.event.repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.whiteeyes.common.event.entity.Event;
import com.whiteeyes.common.event.entity.EventType;

public interface EventRepository extends JpaRepository<Event, Long> {

    /**
     * 구역·종류의 열린(종료 시각 없는) 이벤트. 열린 이벤트는 하나만 두는 것이 규칙이지만(evtdet-004),
     * 어긋나서 둘이 되더라도 판정이 멈추지 않게 가장 먼저 만든 것을 돌려준다.
     */
    Optional<Event> findFirstByZoneIdAndTypeAndEndedAtIsNullOrderByIdAsc(Long zoneId, EventType type);

    /** 구역별 이벤트 수를 한 번에 센다(FNC-C-017 오늘 이벤트 수). */
    @Query("""
            select e.zoneId as zoneId, count(e) as eventCount from Event e
            where e.type in :types and e.startedAt >= :from and e.zoneId in :zoneIds
            group by e.zoneId
            """)
    List<ZoneEventCount> countByZone(
            @Param("zoneIds") Collection<Long> zoneIds,
            @Param("types") Collection<EventType> types,
            @Param("from") LocalDateTime from);

    /** 구역 하나의 이벤트 수. */
    interface ZoneEventCount {

        Long getZoneId();

        long getEventCount();
    }
}
