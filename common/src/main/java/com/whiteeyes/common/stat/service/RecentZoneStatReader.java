package com.whiteeyes.common.stat.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import com.whiteeyes.common.region.entity.Zone;
import com.whiteeyes.common.stat.entity.ZoneStatMinute;
import com.whiteeyes.common.stat.repository.ZoneStatMinuteRepository;

/**
 * 구역들의 최근 1분 통계를 읽고 합산한다(FNC-C-009). 지도 현황과 현재 현황 요약(FNC-C-016)이 같은 기준을 쓴다.
 */
@Component
@RequiredArgsConstructor
public class RecentZoneStatReader {

    // 이 시간 안에 통계가 없으면 '데이터 없음'(FNC-C-009·016 업무 규칙)
    // 최근 1분 통계를 찾는 시간 폭
    private static final Duration RECENT_WINDOW = Duration.ofMinutes(5);
    // zone_stat_minute.density와 같은 자릿수·반올림(DECIMAL 8,4, FNC-A-028)
    // 합산한 밀집도를 나눌 때 쓰는 소수 자릿수
    private static final int DENSITY_SCALE = 4;

    // 1분 통계를 구역 목록으로 한 번에 읽는다
    private final ZoneStatMinuteRepository zoneStatMinuteRepository;

    /**
     * 구역별로 최근 5분 안의 가장 늦은 1분 통계. 통계가 없는 구역은 빠진다.
     *
     * @param now KST 현재 시각
     */
    public Map<Long, ZoneStatMinute> findLatestStats(Collection<Zone> zones, LocalDateTime now) {
        // 구역이 없으면 빈 IN 쿼리를 보내지 않고 끝낸다
        if (zones.isEmpty()) {
            return Map.of();
        }
        // 구역 엔티티에서 번호만 뽑아 IN 쿼리 조건으로 쓴다
        List<Long> zoneIds = zones.stream().map(Zone::getId).toList();
        return zoneStatMinuteRepository
                // 지금부터 5분 이내 행만 읽는다. 통계가 계속 들어오는 구역이라면 최근 몇 개가 나온다
                .findByZoneIdInAndStatAtGreaterThanEqual(zoneIds, now.minus(RECENT_WINDOW))
                .stream()
                .collect(Collectors.toMap(
                        ZoneStatMinute::getZoneId,
                        Function.identity(),
                        // 같은 구역의 행이 여러 개면 가장 늦은 1분을 고른다(toMap은 키가 겹치면 병합 함수가 필요하다)
                        (left, right) -> left.getStatAt().isAfter(right.getStatAt()) ? left : right));
    }

    /**
     * 구역들 중 가장 늦은 1분(T)의 행만 더한다. 밀집도 = count_avg 합 ÷ 그 구역들의 현재 면적 합(conventions.md '혼잡 단계 판정').
     * 캠이 끊겨 T의 행이 없는 구역은 인원·면적 모두에서 빠진다. 통계가 하나도 없으면 빈 값.
     */
    public Optional<LatestMinuteSum> sumAtLatestMinute(
            Collection<Zone> zones, Map<Long, ZoneStatMinute> latestStatByZone) {
        // 구역마다 최신 통계 시각이 다를 수 있다. 그중 가장 늦은 시각(T)을 합산 기준으로 삼는다
        Optional<LocalDateTime> latestMinute = zones.stream()
                .map(zone -> latestStatByZone.get(zone.getId()))
                .filter(Objects::nonNull)
                .map(ZoneStatMinute::getStatAt)
                .max(Comparator.naturalOrder());
        // 통계가 하나도 없으면 합산할 것이 없다
        if (latestMinute.isEmpty()) {
            return Optional.empty();
        }
        // 가장 늦은 1분(T)에 통계가 있는 구역만 남긴다. 다른 분의 값을 섞으면 같은 시점의 합이 아니게 된다
        List<Zone> zonesAtLatest = zones.stream()
                .filter(zone -> {
                    ZoneStatMinute stat = latestStatByZone.get(zone.getId());
                    return stat != null && stat.getStatAt().equals(latestMinute.get());
                })
                .toList();
        List<ZoneStatMinute> stats = zonesAtLatest.stream()
                .map(zone -> latestStatByZone.get(zone.getId()))
                .toList();
        // 인원은 구역별 평균 인원의 합
        BigDecimal countAvg = stats.stream().map(ZoneStatMinute::getCountAvg).reduce(BigDecimal.ZERO, BigDecimal::add);
        // 밀집도를 구역 밀집도의 평균이 아니라 '인원 합 ÷ 면적 합'으로 구하려고 같은 구역들의 면적 합을 구한다
        BigDecimal area = zonesAtLatest.stream().map(Zone::getAreaM2).reduce(BigDecimal.ZERO, BigDecimal::add);
        // 지역 전체 밀집도(명/㎡). 면적이 큰 구역이 더 크게 반영된다
        BigDecimal density = countAvg.divide(area, DENSITY_SCALE, RoundingMode.HALF_UP);
        return Optional.of(new LatestMinuteSum(latestMinute.get(), countAvg, density, stats));
    }

    /**
     * 가장 늦은 1분의 합산 결과.
     *
     * @param stats 합산에 들어간 구역들의 1분 통계
     */
    public record LatestMinuteSum(
            LocalDateTime statAt, BigDecimal countAvg, BigDecimal density, List<ZoneStatMinute> stats) {

        // 컴팩트 생성자: 호출자가 넘긴 목록을 불변 복사해 이후 바뀌어도 이 값이 변하지 않게 한다
        public LatestMinuteSum {
            stats = List.copyOf(stats);
        }
    }
}
