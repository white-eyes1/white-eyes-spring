package com.whiteeyes.common.event.service;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.whiteeyes.common.event.entity.EventType;
import com.whiteeyes.common.event.repository.EventRepository;

/**
 * 구역별 오늘 이벤트 수(FNC-C-017). 혼잡·급증만 세고 캠 이상은 세지 않는다(das-003).
 * 다른 도메인은 이벤트 리포지토리가 아니라 이 서비스를 부른다.
 */
@Service
@RequiredArgsConstructor
public class TodayEventCountService {

    // 오늘 이벤트 수에는 캠 이상을 넣지 않는다
    private static final List<EventType> COUNTED_TYPES = List.of(EventType.CONGESTION, EventType.SURGE);

    // 이벤트 저장소
    private final EventRepository eventRepository;

    /**
     * 구역마다 from 이후에 시작한 혼잡·급증 이벤트 수를 한 번의 쿼리로 센다.
     *
     * @param from 오늘 00:00(KST)
     * @return 구역 번호 → 이벤트 수. 이벤트가 없는 구역은 들어 있지 않으므로 받는 쪽에서 0으로 읽는다
     */
    @Transactional(readOnly = true)
    public Map<Long, Integer> countByZone(Collection<Long> zoneIds, LocalDateTime from) {
        // 빈 목록으로 IN 쿼리를 보내지 않는다
        if (zoneIds.isEmpty()) {
            return Map.of();
        }
        // 집계 행(구역·건수)을 구역 번호 → 건수 맵으로 옮긴다
        Map<Long, Integer> countByZone = new HashMap<>();
        for (EventRepository.ZoneEventCount row : eventRepository.countByZone(zoneIds, COUNTED_TYPES, from)) {
            // 건수는 long으로 오지만 응답은 int이므로 범위를 넘으면 예외가 나게 toIntExact를 쓴다
            countByZone.put(row.getZoneId(), Math.toIntExact(row.getEventCount()));
        }
        return countByZone;
    }

    /**
     * 구역들 전체의 오늘 이벤트 수 합계(지역·구역 하나의 대시보드 요약).
     *
     * @param zoneIds 삭제된 구역도 포함한 구역 번호
     * @param from 오늘 00:00(KST)
     */
    @Transactional(readOnly = true)
    public int countTotal(Collection<Long> zoneIds, LocalDateTime from) {
        // 구역별 건수를 구해 모두 더한다. 위 메서드를 재사용해 쿼리 조건을 한 곳에서만 관리한다
        return countByZone(zoneIds, from).values().stream()
                .mapToInt(Integer::intValue)
                .sum();
    }
}
