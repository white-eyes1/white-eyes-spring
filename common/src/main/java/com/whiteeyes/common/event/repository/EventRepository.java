package com.whiteeyes.common.event.repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.whiteeyes.common.event.entity.Event;
import com.whiteeyes.common.event.entity.EventType;

/**
 * 이벤트 저장소(tables.md 'event'). 혼잡·급증·캠 이상 판정(FNC-A-033~035)과 오늘 이벤트 수가 함께 쓰고,
 * 이벤트 목록·요약(FNC-A-036)은 {@link EventSpecifications}로 조건을 조합해 조회한다.
 */
public interface EventRepository extends JpaRepository<Event, Long>, JpaSpecificationExecutor<Event> {

    /** 그 구역의 행이 하나라도 있는지(구역 삭제 FNC-A-017 처리 순서 2의 이력 확인). */
    boolean existsByZoneId(Long zoneId);

    /**
     * 구역·종류의 열린(종료 시각 없는) 이벤트. 열린 이벤트는 하나만 두는 것이 규칙이지만(evtdet-004),
     * 어긋나서 둘이 되더라도 판정이 멈추지 않게 가장 먼저 만든 것을 돌려준다.
     */
    Optional<Event> findFirstByZoneIdAndTypeAndEndedAtIsNullOrderByIdAsc(Long zoneId, EventType type);

    /** 캠의 열린 이벤트(캠 이상 FNC-A-035). 어긋나 둘이 되어도 멈추지 않게 가장 먼저 만든 것을 돌려준다. */
    Optional<Event> findFirstByCameraIdAndTypeAndEndedAtIsNullOrderByIdAsc(Long cameraId, EventType type);

    /** 캠의 열린 이벤트 전부. 어긋나 둘 이상이어도 모두 닫을 수 있게 목록으로 돌려준다(캠 이상 FNC-A-035). */
    List<Event> findByCameraIdAndTypeAndEndedAtIsNull(Long cameraId, EventType type);

    /** 종류별 열린 이벤트 전부(캠 이상 감지가 조건이 풀린 것을 닫을 때, FNC-A-035). */
    List<Event> findByTypeAndEndedAtIsNull(EventType type);

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
