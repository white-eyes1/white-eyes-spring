package com.whiteeyes.common.stat.service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.whiteeyes.common.stat.entity.ZoneNowCount;
import com.whiteeyes.common.stat.repository.ZoneNowCountRepository;

/**
 * 구역별 현재 인원(zone_now_count)을 읽는다(FNC-C-016·022, FNC-A-054). NOW_COUNT_FRESH_SEC보다 오래된 값은 없는 것으로 본다.
 * 1분 통계와 섞이지 않도록 이 클래스만 이 표를 읽는다.
 */
@Component
public class NowCountReader {

    private final ZoneNowCountRepository zoneNowCountRepository;
    private final Duration fresh;

    public NowCountReader(
            ZoneNowCountRepository zoneNowCountRepository,
            // 테스트 application.yml이 main 설정을 가리므로 yml을 거치지 않고 환경변수를 바로 읽는다(기본 15초, conventions.md)
            @Value("${NOW_COUNT_FRESH_SEC:15}") long freshSec) {
        this.zoneNowCountRepository = zoneNowCountRepository;
        this.fresh = Duration.ofSeconds(freshSec);
    }

    /** 전체 구역 중 신선한 현재 인원. 통계 SSE가 5초에 한 번 부른다. */
    public List<ZoneNowCount> findAllFresh(LocalDateTime now) {
        return zoneNowCountRepository.findByUpdatedAtGreaterThanEqual(now.minus(fresh));
    }

    /** 구역별 신선한 현재 인원. 없는 구역은 빠진다. */
    public Map<Long, ZoneNowCount> findFresh(Collection<Long> zoneIds, LocalDateTime now) {
        if (zoneIds.isEmpty()) {
            return Map.of();
        }
        return zoneNowCountRepository
                .findByZoneIdInAndUpdatedAtGreaterThanEqual(zoneIds, now.minus(fresh))
                .stream()
                .collect(Collectors.toMap(ZoneNowCount::getZoneId, count -> count));
    }

    /** 신선한 구역들의 현재 인원 합. 신선한 값이 하나도 없으면 빈 값(화면은 1분 평균으로 되돌린다). */
    public OptionalInt sumFresh(Collection<Long> zoneIds, LocalDateTime now) {
        Map<Long, ZoneNowCount> found = findFresh(zoneIds, now);
        if (found.isEmpty()) {
            return OptionalInt.empty();
        }
        return OptionalInt.of(found.values().stream().mapToInt(ZoneNowCount::getPersonCount).sum());
    }
}
