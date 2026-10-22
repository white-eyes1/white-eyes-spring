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

import org.springframework.stereotype.Component;

import com.whiteeyes.common.region.entity.Zone;
import com.whiteeyes.common.stat.entity.ZoneStatMinute;
import com.whiteeyes.common.stat.repository.ZoneStatMinuteRepository;

/**
 * 구역들의 최근 1분 통계를 읽고 합산한다(FNC-C-009). 지도 현황과 현재 현황 요약(FNC-C-016)이 같은 기준을 쓴다.
 */
@Component
public class RecentZoneStatReader {

    // 이 시간 안에 통계가 없으면 '데이터 없음'(FNC-C-009·016 업무 규칙)
    private static final Duration RECENT_WINDOW = Duration.ofMinutes(5);
    // zone_stat_minute.density와 같은 자릿수·반올림(DECIMAL 8,4, FNC-A-028)
    private static final int DENSITY_SCALE = 4;

    private final ZoneStatMinuteRepository zoneStatMinuteRepository;

    public RecentZoneStatReader(ZoneStatMinuteRepository zoneStatMinuteRepository) {
        this.zoneStatMinuteRepository = zoneStatMinuteRepository;
    }

    /**
     * 구역별로 최근 5분 안의 가장 늦은 1분 통계. 통계가 없는 구역은 빠진다.
     *
     * @param now KST 현재 시각
     */
    public Map<Long, ZoneStatMinute> findLatestStats(Collection<Zone> zones, LocalDateTime now) {
        if (zones.isEmpty()) {
            return Map.of();
        }
        List<Long> zoneIds = zones.stream().map(Zone::getId).toList();
        return zoneStatMinuteRepository
                .findByZoneIdInAndStatAtGreaterThanEqual(zoneIds, now.minus(RECENT_WINDOW))
                .stream()
                .collect(Collectors.toMap(
                        ZoneStatMinute::getZoneId,
                        Function.identity(),
                        (left, right) -> left.getStatAt().isAfter(right.getStatAt()) ? left : right));
    }

    /**
     * 구역들 중 가장 늦은 1분(T)의 행만 더한다. 밀집도 = count_avg 합 ÷ 그 구역들의 현재 면적 합(conventions.md '혼잡 단계 판정').
     * 캠이 끊겨 T의 행이 없는 구역은 인원·면적 모두에서 빠진다. 통계가 하나도 없으면 빈 값.
     */
    public Optional<LatestMinuteSum> sumAtLatestMinute(
            Collection<Zone> zones, Map<Long, ZoneStatMinute> latestStatByZone) {
        Optional<LocalDateTime> latestMinute = zones.stream()
                .map(zone -> latestStatByZone.get(zone.getId()))
                .filter(Objects::nonNull)
                .map(ZoneStatMinute::getStatAt)
                .max(Comparator.naturalOrder());
        if (latestMinute.isEmpty()) {
            return Optional.empty();
        }
        List<Zone> zonesAtLatest = zones.stream()
                .filter(zone -> {
                    ZoneStatMinute stat = latestStatByZone.get(zone.getId());
                    return stat != null && stat.getStatAt().equals(latestMinute.get());
                })
                .toList();
        List<ZoneStatMinute> stats = zonesAtLatest.stream()
                .map(zone -> latestStatByZone.get(zone.getId()))
                .toList();
        BigDecimal countAvg = stats.stream().map(ZoneStatMinute::getCountAvg).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal area = zonesAtLatest.stream().map(Zone::getAreaM2).reduce(BigDecimal.ZERO, BigDecimal::add);
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

        public LatestMinuteSum {
            stats = List.copyOf(stats);
        }
    }
}
