package com.whiteeyes.admin.stat.service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Component;

/**
 * 구역·분별 1분 집계용 메모리 누적기(FNC-A-024)와 마감 기록.
 *
 * <p>'이미 마감된 분'은 시각이 아니라 실제로 마감한 (구역, 분) 기록으로 판정한다. 관리자 앱이 재시작하면 기록이 비어 있어,
 * 분석 서버가 재전송한 꺼져 있던 동안의 결과를 받아 다시 채울 수 있다(FNC-S-006 검수 기준).
 * 결과 수신과 마감(FNC-A-028)이 다른 스레드에서 오므로 모든 접근을 이 객체의 잠금으로 묶는다.
 */
@Component
public class MinuteAccumulatorStore {

    // 분석 서버의 재전송 한도(RESULT_RETRY_BUFFER_SEC 300초)보다 길게 둬서, 마감 뒤 늦게 온 재전송분이 같은 분을 새 누적기로 다시 만들지 않게 한다
    private static final Duration CLOSED_MINUTE_RETENTION = Duration.ofMinutes(10);

    private final Map<MinuteKey, MinuteAccumulator> accumulators = new HashMap<>();
    private final Set<MinuteKey> closedMinutes = new HashSet<>();

    /**
     * 1초 결과를 그 초가 속한 분의 누적기에 넣는다. 같은 초가 이미 있으면 덮어쓴다.
     *
     * @param second KST, 초 단위로 자른 시각
     * @return 넣었으면 true, 그 분이 이미 마감돼 버렸으면 false
     */
    public synchronized boolean add(long zoneId, LocalDateTime second, SecondResult result) {
        MinuteKey key = new MinuteKey(zoneId, second.truncatedTo(ChronoUnit.MINUTES));
        if (closedMinutes.contains(key)) {
            return false;
        }
        accumulators
                .computeIfAbsent(key, minuteKey -> new MinuteAccumulator(minuteKey.zoneId(), minuteKey.minute()))
                .put(second, result);
        return true;
    }

    /**
     * 그 구역·분을 마감한다. 이후 같은 분의 결과는 {@link #add}에서 버린다.
     *
     * @return 마감한 누적기. 받은 결과가 하나도 없었으면 빈 값
     */
    public synchronized Optional<MinuteAccumulator> closeMinute(long zoneId, LocalDateTime minute) {
        MinuteKey key = new MinuteKey(zoneId, minute);
        closedMinutes.add(key);
        LocalDateTime retentionStart = minute.minus(CLOSED_MINUTE_RETENTION);
        closedMinutes.removeIf(closed -> closed.minute().isBefore(retentionStart));
        return Optional.ofNullable(accumulators.remove(key));
    }

    /**
     * 분 끝 시각이 cutoff 이하인 누적기를 모두 마감해 꺼낸다(FNC-A-028 처리 순서 1). 분·구역 순서로 돌려준다.
     *
     * @param cutoff 지금 − MINUTE_CLOSE_GRACE_SEC. 14:03 구간은 cutoff가 14:04:00 이상일 때 마감된다
     */
    public synchronized List<MinuteAccumulator> closeMinutesEndedBy(LocalDateTime cutoff) {
        List<MinuteKey> endedKeys = accumulators.keySet().stream()
                .filter(key -> !key.minute().plusMinutes(1).isAfter(cutoff))
                .sorted(Comparator.comparing(MinuteKey::minute).thenComparing(MinuteKey::zoneId))
                .toList();
        return endedKeys.stream()
                .map(key -> closeMinute(key.zoneId(), key.minute()).orElseThrow())
                .toList();
    }

    /**
     * 구역마다 아직 마감되지 않은 누적기들 중 가장 최근 초의 결과(FNC-A-054). 1분 통계와 달리 누적기를 비우지 않고 읽기만 한다.
     * 결과를 받는 스레드와 겹치지 않게 잠금 안에서 읽는다.
     */
    public synchronized Map<Long, LatestSecond> findLatestSecondByZone() {
        Map<Long, LatestSecond> latestByZone = new HashMap<>();
        for (MinuteAccumulator accumulator : accumulators.values()) {
            accumulator.latestSecond().ifPresent(entry -> latestByZone.merge(
                    accumulator.getZoneId(),
                    new LatestSecond(entry.getKey(), entry.getValue().personCount()),
                    (left, right) -> left.second().isAfter(right.second()) ? left : right));
        }
        return latestByZone;
    }

    /** 한 구역이 가장 최근에 받은 초와 그 초의 인원. */
    public record LatestSecond(LocalDateTime second, int personCount) {}

    private record MinuteKey(long zoneId, LocalDateTime minute) {}
}
