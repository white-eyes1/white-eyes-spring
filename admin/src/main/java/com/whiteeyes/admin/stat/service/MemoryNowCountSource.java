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

    // 구역별 최근 1초 결과가 들어 있는 메모리 누적기 저장소
    private final MinuteAccumulatorStore minuteAccumulatorStore;
    // 이 시간보다 오래된 초는 '현재 인원'으로 쓰지 않는다
    private final Duration fresh;

    public MemoryNowCountSource(
            MinuteAccumulatorStore minuteAccumulatorStore,
            // 테스트 application.yml이 main 설정을 가리므로 yml을 거치지 않고 환경변수를 바로 읽는다(기본 15초, conventions.md)
            @Value("${NOW_COUNT_FRESH_SEC:15}") long freshSec) {
        this.minuteAccumulatorStore = minuteAccumulatorStore;
        this.fresh = Duration.ofSeconds(freshSec);
    }

    @Override
    /** 요청한 구역 중 최신 인원이 있는 구역만 구역 번호 → 현재 인원으로 돌려준다. 오래된 구역은 빠진다. */
    public Map<Long, NowCount> findFresh(Collection<Long> zoneIds, LocalDateTime now) {
        // 찾을 구역이 없으면 누적기를 훑을 필요가 없다
        if (zoneIds.isEmpty()) {
            return Map.of();
        }
        // 아래 필터에서 contains를 반복하므로 해시 집합으로 바꿔 둔다
        Set<Long> wanted = new HashSet<>(zoneIds);
        return findAllFresh(now).stream()
                .filter(count -> wanted.contains(count.zoneId()))
                .collect(Collectors.toMap(NowCount::zoneId, count -> count));
    }

    @Override
    /** 전 구역의 현재 인원 중 아직 신선한 것만 돌려준다. */
    public List<NowCount> findAllFresh(LocalDateTime now) {
        // 이 시각 이전의 초는 오래된 값이다. 정확히 경계인 초는 신선한 것으로 본다(isBefore로 걸러내므로)
        LocalDateTime oldest = now.minus(fresh);
        // 구역별 가장 최근 1초 결과를 읽어, 신선한 것만 현재 인원 값으로 바꾼다
        return minuteAccumulatorStore.findLatestSecondByZone().entrySet().stream()
                .filter(entry -> !entry.getValue().second().isBefore(oldest))
                .map(entry -> new NowCount(
                        entry.getKey(),
                        entry.getValue().personCount(),
                        entry.getValue().second()))
                .toList();
    }
}
