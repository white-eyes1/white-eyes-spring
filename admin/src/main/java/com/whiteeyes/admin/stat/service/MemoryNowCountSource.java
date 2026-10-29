package com.whiteeyes.admin.stat.service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.whiteeyes.common.stat.service.NowCountSource;

/**
 * 현재 인원 제공(FNC-A-054). 메모리 누적기(MinuteAccumulatorStore)의 구역별 가장 최근 초 인원을 바로 읽는다.
 * DB에 저장하지 않고, 그 초가 NOW_COUNT_FRESH_SEC(15초)보다 오래됐으면 쓰지 않는다. 읽기만 하므로 1분 통계 저장(FNC-A-028)에 영향이 없다.
 */
@Component
public class MemoryNowCountSource implements NowCountSource {

    private final MinuteAccumulatorStore minuteAccumulatorStore;
    private final Duration fresh;

    public MemoryNowCountSource(
            MinuteAccumulatorStore minuteAccumulatorStore,
            // 테스트 application.yml이 main 설정을 가리므로 yml을 거치지 않고 환경변수를 바로 읽는다(기본 15초, conventions.md)
            @Value("${NOW_COUNT_FRESH_SEC:15}") long freshSec) {
        this.minuteAccumulatorStore = minuteAccumulatorStore;
        this.fresh = Duration.ofSeconds(freshSec);
    }

    @Override
    public Map<Long, NowCount> findFresh(Collection<Long> zoneIds, LocalDateTime now) {
        if (zoneIds.isEmpty()) {
            return Map.of();
        }
        Set<Long> wanted = new HashSet<>(zoneIds);
        return findAllFresh(now).stream()
                .filter(count -> wanted.contains(count.zoneId()))
                .collect(Collectors.toMap(NowCount::zoneId, count -> count));
    }

    @Override
    public List<NowCount> findAllFresh(LocalDateTime now) {
        LocalDateTime oldest = now.minus(fresh);
        return minuteAccumulatorStore.findLatestSecondByZone().entrySet().stream()
                .filter(entry -> !entry.getValue().second().isBefore(oldest))
                .map(entry -> new NowCount(entry.getKey(), entry.getValue().personCount(), entry.getValue().second()))
                .toList();
    }
}
